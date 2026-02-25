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

    public static String flinkCheckpointDir() {
        return get("FLINK_CHECKPOINT_DIR", "./checkpoint");
    }
}
