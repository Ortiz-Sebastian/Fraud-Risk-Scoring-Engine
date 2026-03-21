package com.riskengine.engine;

import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import com.riskengine.common.model.TransactionEvent;
import com.riskengine.engine.fraud.VelocityDetector;
import com.riskengine.engine.sink.CassandraRiskScoreSink;
import com.riskengine.engine.sink.CassandraTransactionSink;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class StreamingJob {

    private static final Logger log = LoggerFactory.getLogger(StreamingJob.class);

    public static void main(String[] args) throws Exception {
        log.info("Risk Engine starting | kafka={} topic={} cassandra={}:{} velocityThreshold={}",
                AppConfig.kafkaBootstrapServers(), AppConfig.kafkaTopic(),
                AppConfig.cassandraHost(), AppConfig.cassandraPort(),
                AppConfig.velocityThreshold());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(60_000);

        KafkaSource<TransactionEvent> kafkaSource = KafkaSource.<TransactionEvent>builder()
                .setBootstrapServers(AppConfig.kafkaBootstrapServers())
                .setTopics(AppConfig.kafkaTopic())
                .setGroupId("risk-engine")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new TransactionEventDeserializer())
                .build();

        // Event-time watermarks with 5-second bounded out-of-orderness.
        // withIdleness is required whenever Flink parallelism exceeds the number of Kafka partitions
        // (the default case in local mode). Idle subtasks that receive no partition assignment would
        // otherwise hold the global watermark at -∞ indefinitely, preventing all windows from firing.
        WatermarkStrategy<TransactionEvent> watermarkStrategy =
                WatermarkStrategy.<TransactionEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((event, recordTs) -> event.eventTs().toEpochMilli())
                        .withIdleness(Duration.ofSeconds(10));

        DataStream<TransactionEvent> events = env.fromSource(
                kafkaSource,
                watermarkStrategy,
                "Transaction Kafka Source"
        );

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

        velocityScores.addSink(new CassandraRiskScoreSink())
                      .name("Cassandra Risk Score Sink");

        env.execute("Fraud Risk Engine");
    }
}
