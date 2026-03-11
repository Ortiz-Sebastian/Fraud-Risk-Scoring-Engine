package com.riskengine.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.TransactionEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for the transaction event producer.
 *
 * CLI arguments:
 *   --rate     N    events per second to emit (default: 100)
 *   --mode     M    "normal" or "attack"       (default: normal)
 *   --duration S    run for S seconds, then stop; omit to run forever
 *
 * Environment variables (via AppConfig):
 *   KAFKA_BOOTSTRAP_SERVERS  (default: localhost:9092)
 *   KAFKA_TOPIC              (default: risk.transactions)
 */
public class TransactionProducer {

    private static final Logger log = LoggerFactory.getLogger(TransactionProducer.class);

    public static void main(String[] args) throws Exception {
        int eventsPerSec = parseIntArg(args, "--rate",     100);
        int durationSec  = parseIntArg(args, "--duration", -1);
        ProducerMode mode = parseMode(args);

        log.info("Starting TransactionProducer | mode={} rate={}/s duration={}",
                mode, eventsPerSec, durationSec < 0 ? "∞" : durationSec + "s");

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        EventGenerator generator = new EventGenerator(mode);
        String topic = AppConfig.kafkaTopic();

        AtomicLong sent   = new AtomicLong(0);
        AtomicLong errors = new AtomicLong(0);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                log.info("Shutdown | sent={} errors={}", sent.get(), errors.get())));

        long endMs = durationSec > 0
                ? System.currentTimeMillis() + (durationSec * 1000L)
                : Long.MAX_VALUE;

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(buildKafkaProps())) {

            // Nanosecond-precision rate limiter.
            // nextSendNs is reset from "now" after each send to avoid catch-up bursts
            // when the send itself takes longer than the interval.
            long intervalNs  = 1_000_000_000L / eventsPerSec;
            long nextSendNs  = System.nanoTime();

            while (System.currentTimeMillis() < endMs) {
                long nowNs = System.nanoTime();

                if (nowNs >= nextSendNs) {
                    TransactionEvent event = generator.next();
                    String payload = mapper.writeValueAsString(event);

                    // Partition by user_id so all events for a user land on the same partition,
                    // which is required for correct stateful velocity tracking in Flink.
                    ProducerRecord<String, String> record =
                            new ProducerRecord<>(topic, event.userId(), payload);

                    producer.send(record, (metadata, ex) -> {
                        if (ex != null) {
                            log.error("Send failed: {}", ex.getMessage());
                            errors.incrementAndGet();
                        } else {
                            long count = sent.incrementAndGet();
                            if (count % 1_000 == 0) {
                                log.info("Sent {} events | partition={} offset={}",
                                        count, metadata.partition(), metadata.offset());
                            }
                        }
                    });

                    // Advance from current time to prevent burst catch-up
                    nextSendNs = nowNs + intervalNs;

                } else {
                    long gapNs = nextSendNs - nowNs;
                    // Only call Thread.sleep when the gap is large enough to matter;
                    // below ~1ms the OS scheduler granularity makes sleep unreliable,
                    // so we spin instead.
                    if (gapNs > 1_000_000L) {
                        Thread.sleep(gapNs / 2_000_000); // sleep half the gap to avoid overshoot
                    }
                }
            }

            producer.flush();
            log.info("Producer finished | sent={} errors={}", sent.get(), errors.get());
        }
    }

    // ── Kafka configuration ───────────────────────────────────────────────────

    private static Properties buildKafkaProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  AppConfig.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Durability: wait for all in-sync replicas to acknowledge
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // Idempotent producer — prevents duplicates on retry
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Retry on transient failures; idempotence keeps ordering safe
        props.put(ProducerConfig.RETRIES_CONFIG, 5);

        // Micro-batching: collect messages for up to 5ms before flushing,
        // which improves throughput at the cost of a small latency increase.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32_768); // 32 KB

        // LZ4 balances compression ratio and CPU cost well for JSON payloads
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        return props;
    }

    // ── Argument parsing ──────────────────────────────────────────────────────

    private static int parseIntArg(String[] args, String flag, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }

    private static ProducerMode parseMode(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--mode")) {
                return ProducerMode.valueOf(args[i + 1].toUpperCase());
            }
        }
        return ProducerMode.NORMAL;
    }
}
