package com.riskengine.engine;

import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import com.riskengine.common.model.TransactionEvent;
import com.riskengine.engine.fraud.DeviceProfileDetector;
import com.riskengine.engine.fraud.IpBurstDetector;
import com.riskengine.engine.fraud.VelocityDetector;
import com.riskengine.engine.preprocess.EventDeduplicationFunction;
import com.riskengine.engine.preprocess.LateEventRouterFunction;
import com.riskengine.engine.sink.CassandraLateEventSink;
import com.riskengine.engine.sink.CassandraRiskScoreSink;
import com.riskengine.engine.sink.CassandraTransactionSink;
import com.riskengine.engine.sink.ElasticsearchRiskScoreSink;
import com.riskengine.engine.sink.PostgresRiskScoreSink;
import com.riskengine.engine.sink.RedisRiskScoreSink;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class StreamingJob {

    private static final Logger log = LoggerFactory.getLogger(StreamingJob.class);
    private static final OutputTag<TransactionEvent> LATE_EVENTS_TAG =
            new OutputTag<>("late-events") {};

    public static void main(String[] args) throws Exception {
        log.info("Risk Engine starting | kafka={} topic={} cassandra={}:{} velocityThreshold={} ipBurstThreshold={} newDeviceAmountThreshold={} deviceStateRetentionMin={}",
                AppConfig.kafkaBootstrapServers(), AppConfig.kafkaTopic(),
                AppConfig.cassandraHost(), AppConfig.cassandraPort(),
                AppConfig.velocityThreshold(), AppConfig.ipBurstThreshold(),
                AppConfig.newDeviceAmountThreshold(), AppConfig.deviceStateRetentionMinutes());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(60_000);

        KafkaSource<TransactionEvent> kafkaSource = KafkaSource.<TransactionEvent>builder()
                .setBootstrapServers(AppConfig.kafkaBootstrapServers())
                .setTopics(AppConfig.kafkaTopic())
                .setGroupId("risk-engine")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new TransactionEventDeserializer())
                .build();

        int watermarkOutOfOrdernessSec = AppConfig.watermarkOutOfOrdernessSeconds();
        int dedupRetentionMin = AppConfig.dedupStateRetentionMinutes();

        // Event-time watermarks with bounded out-of-orderness.
        // withIdleness is required whenever Flink parallelism exceeds the number of Kafka partitions
        // (the default case in local mode). Idle subtasks that receive no partition assignment would
        // otherwise hold the global watermark at -∞ indefinitely, preventing all windows from firing.
        WatermarkStrategy<TransactionEvent> watermarkStrategy =
                WatermarkStrategy.<TransactionEvent>forBoundedOutOfOrderness(Duration.ofSeconds(watermarkOutOfOrdernessSec))
                        .withTimestampAssigner((event, recordTs) -> event.eventTs().toEpochMilli())
                        .withIdleness(Duration.ofSeconds(Math.max(10, watermarkOutOfOrdernessSec * 2L)));

        DataStream<TransactionEvent> sourceEvents = env.fromSource(
                kafkaSource,
                watermarkStrategy,
                "Transaction Kafka Source"
        );

        // ── Phase 6: watermark-based late-event routing + event_id dedup ──────────────────
        SingleOutputStreamOperator<TransactionEvent> onTimeEvents = sourceEvents
                .process(new LateEventRouterFunction(LATE_EVENTS_TAG))
                .name("Late Event Router [event_ts vs watermark]");

        DataStream<TransactionEvent> lateEvents = onTimeEvents.getSideOutput(LATE_EVENTS_TAG);

        lateEvents
                .addSink(new CassandraLateEventSink())
                .name("Cassandra Late Events Sink");

        DataStream<TransactionEvent> events = onTimeEvents
                .keyBy(TransactionEvent::eventId)
                .process(new EventDeduplicationFunction(dedupRetentionMin))
                .name("Event Deduplication [event_id + TTL]");

        log.info("Phase 6 active | watermarkOutOfOrdernessSec={} dedupRetentionMin={}",
                watermarkOutOfOrdernessSec, dedupRetentionMin);

        // ── Phase 2: raw event pass-through ───────────────────────────────────
        events.addSink(new CassandraTransactionSink())
              .name("Cassandra Transaction Sink");

        // ── Phase 3: velocity detection ───────────────────────────────────────
        // 5-minute sliding window, advancing every 1 minute, keyed per user_id.
        // A RiskScore is emitted only when the transaction count exceeds the threshold.
        DataStream<RiskScore> velocityScores = events
                .keyBy(TransactionEvent::userId)
                .window(SlidingEventTimeWindows.of(Time.minutes(5), Time.minutes(1)))
                .aggregate(new VelocityDetector.CountAggregator(), new VelocityDetector.WindowEvaluator())
                .name("Velocity Detection [5m window / 1m slide]");

        // ── Phase 4: IP burst detection ───────────────────────────────────────
        // 2-minute sliding window, advancing every 30 seconds, keyed per IP.
        // A RiskScore is emitted when the number of distinct users on an IP exceeds the threshold.
        DataStream<RiskScore> ipBurstScores = events
                .keyBy(TransactionEvent::ip)
                .window(SlidingEventTimeWindows.of(Time.minutes(2), Time.seconds(30)))
                .aggregate(new IpBurstDetector.DistinctUserAggregator(), new IpBurstDetector.WindowEvaluator())
                .name("IP Burst Detection [2m window / 30s slide]");

        // ── Phase 5: device profiling ─────────────────────────────────────
        // Stateful per-device_id check: flags the first-ever transaction from a new
        // device when the amount exceeds the threshold. State TTL acts as a session
        // inactivity gap — dormant devices are eventually forgotten and re-flaggable.
        DataStream<RiskScore> deviceScores = events
                .keyBy(TransactionEvent::deviceId)
                .process(new DeviceProfileDetector.NewDeviceFunction())
                .name("Device Profile Detection [new device + large purchase]");

        // Merge all rule outputs into a single stream, then fan out to every sink (Phase 7).
        // union() is a Flink primitive that merges streams without any shuffle or re-keying.
        DataStream<RiskScore> riskScores = velocityScores.union(ipBurstScores, deviceScores);

        riskScores.addSink(new CassandraRiskScoreSink())
                  .name("Cassandra Risk Score Sink");
        riskScores.addSink(new PostgresRiskScoreSink())
                  .name("PostgreSQL Risk Score Sink");
        riskScores.addSink(new RedisRiskScoreSink())
                  .name("Redis Risk Score Sink");
        riskScores.addSink(new ElasticsearchRiskScoreSink())
                  .name("Elasticsearch Risk Score Sink");

        env.execute("Fraud Risk Engine");
    }
}
