package com.riskengine.common.config;

import java.util.Optional;

public final class AppConfig {

    private AppConfig() {}

    public static String get(String key, String defaultValue) {
        return Optional.ofNullable(System.getenv(key)).orElse(defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        String val = System.getenv(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    public static String kafkaBootstrapServers() {
        return get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    }

    public static String kafkaTopic() {
        return get("KAFKA_TOPIC", "risk.transactions");
    }

    public static String postgresUrl() {
        String host = get("POSTGRES_HOST", "localhost");
        int port = getInt("POSTGRES_PORT", 5432);
        String db = get("POSTGRES_DB", "fraud_db");
        return "jdbc:postgresql://" + host + ":" + port + "/" + db;
    }

    public static String postgresUser() {
        return get("POSTGRES_USER", "fraud_user");
    }

    public static String postgresPassword() {
        return get("POSTGRES_PASSWORD", "fraud_pass");
    }

    public static String redisHost() {
        return get("REDIS_HOST", "localhost");
    }

    public static int redisPort() {
        return getInt("REDIS_PORT", 6379);
    }

    public static String elasticsearchUrl() {
        String host = get("ELASTICSEARCH_HOST", "localhost");
        int port = getInt("ELASTICSEARCH_PORT", 9200);
        return "http://" + host + ":" + port;
    }

    public static String cassandraHost() {
        return get("CASSANDRA_HOST", "localhost");
    }

    public static int cassandraPort() {
        return getInt("CASSANDRA_PORT", 9042);
    }

    public static String cassandraKeyspace() {
        return get("CASSANDRA_KEYSPACE", "fraud_engine");
    }

    public static String flinkCheckpointDir() {
        return get("FLINK_CHECKPOINT_DIR", "./checkpoint");
    }

    /**
     * Maximum number of transactions a single user_id may submit within a 5-minute
     * sliding window before being flagged with USER_VELOCITY.
     */
    public static int velocityThreshold() {
        return getInt("VELOCITY_THRESHOLD", 10);
    }

    /**
     * Maximum number of distinct user_ids that may transact from a single IP address
     * within a 2-minute sliding window before being flagged with IP_BURST.
     */
    public static int ipBurstThreshold() {
        return getInt("IP_BURST_THRESHOLD", 5);
    }

    /**
     * Minimum transaction amount (in dollars) that triggers a NEW_DEVICE_HIGH_VALUE flag
     * when seen on a device_id for the very first time.
     */
    public static int newDeviceAmountThreshold() {
        return getInt("NEW_DEVICE_AMOUNT_THRESHOLD", 500);
    }

    /**
     * Minutes of inactivity after which a device_id's "seen" state expires.
     * Once expired, the next transaction from that device is treated as a first-time event.
     * Acts as the session-inactivity gap for device profiling.
     */
    public static int deviceStateRetentionMinutes() {
        return getInt("DEVICE_STATE_RETENTION_MINUTES", 1440);
    }

    /**
     * Minutes to retain seen event_id entries for stream deduplication.
     * Bounds ValueState size used by event deduplication.
     */
    public static int dedupStateRetentionMinutes() {
        return getInt("DEDUP_STATE_RETENTION_MINUTES", 60);
    }

    /**
     * Allowed event-time lateness (out-of-orderness) used by WatermarkStrategy.
     * Events older than the current watermark are routed to a late-event side output.
     */
    public static int watermarkOutOfOrdernessSeconds() {
        return getInt("WATERMARK_OUT_OF_ORDERNESS_SECONDS", 10);
    }
}
