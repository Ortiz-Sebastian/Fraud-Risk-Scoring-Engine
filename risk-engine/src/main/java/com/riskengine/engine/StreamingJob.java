package com.riskengine.engine;

import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.TransactionEvent;
import com.riskengine.engine.sink.CassandraTransactionSink;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamingJob {

    private static final Logger log = LoggerFactory.getLogger(StreamingJob.class);

    public static void main(String[] args) throws Exception {
        log.info("Risk Engine starting | kafka={} topic={} cassandra={}:{}",
                AppConfig.kafkaBootstrapServers(), AppConfig.kafkaTopic(),
                AppConfig.cassandraHost(), AppConfig.cassandraPort());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(60_000);

        KafkaSource<TransactionEvent> kafkaSource = KafkaSource.<TransactionEvent>builder()
                .setBootstrapServers(AppConfig.kafkaBootstrapServers())
                .setTopics(AppConfig.kafkaTopic())
                .setGroupId("risk-engine")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new TransactionEventDeserializer())
                .build();

        DataStream<TransactionEvent> events = env.fromSource(
                kafkaSource,
                WatermarkStrategy.noWatermarks(),
                "Transaction Kafka Source"
        );

        events.addSink(new CassandraTransactionSink())
              .name("Cassandra Transaction Sink");

        env.execute("Fraud Risk Engine");
    }
}
