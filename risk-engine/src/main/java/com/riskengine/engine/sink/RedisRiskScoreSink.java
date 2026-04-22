package com.riskengine.engine.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes flagged {@link RiskScore} records to Redis as {@code HSET fraud:flagged:<event_id>}
 * with a configurable TTL for API hot-path lookups.
 */
public class RedisRiskScoreSink extends RichSinkFunction<RiskScore> {

    private static final Logger log = LoggerFactory.getLogger(RedisRiskScoreSink.class);

    private transient JedisPooled jedis;
    private transient ObjectMapper objectMapper;

    @Override
    public void open(Configuration parameters) {
        String host = AppConfig.redisHost();
        int port = AppConfig.redisPort();
        log.info("Connecting to Redis for risk scores | host={} port={}", host, port);
        jedis = new JedisPooled(host, port);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public void invoke(RiskScore score, Context context) throws JsonProcessingException {
        if (!score.flagged()) {
            return;
        }

        String key = "fraud:flagged:" + score.eventId();
        Map<String, String> fields = new HashMap<>();
        fields.put("risk_score", String.valueOf(score.riskScore()));
        fields.put("flagged", "true");
        fields.put("reasons", objectMapper.writeValueAsString(score.reasons() != null ? score.reasons() : List.of()));
        fields.put("rule_version", score.ruleVersion());
        fields.put("scored_at", Instant.now().toString());

        jedis.hset(key, fields);
        jedis.expire(key, AppConfig.redisRiskScoreTtlSeconds());

        log.debug("Flagged risk score cached in Redis | key={}", key);
    }

    @Override
    public void close() {
        if (jedis != null) {
            jedis.close();
            log.info("Redis risk-score client closed");
        }
    }
}
