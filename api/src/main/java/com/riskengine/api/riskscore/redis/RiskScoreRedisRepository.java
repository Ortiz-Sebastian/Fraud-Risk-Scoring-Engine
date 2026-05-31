package com.riskengine.api.riskscore.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskengine.api.riskscore.LiveRiskScoreResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.JedisPooled;

@Repository
public class RiskScoreRedisRepository {

    private static final TypeReference<List<String>> REASONS_TYPE = new TypeReference<>() {};

    private final JedisPooled jedis;
    private final ObjectMapper objectMapper;

    public RiskScoreRedisRepository(JedisPooled jedis, ObjectMapper objectMapper) {
        this.jedis = jedis;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads a flagged score hash written by Flink's {@code RedisRiskScoreSink}.
     *
     * @return empty when the key is absent or expired
     */
    public Optional<LiveRiskScoreResponse> findByEventId(String eventId) {
        Map<String, String> fields = jedis.hgetAll(RiskScoreRedisKeys.keyFor(eventId));
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parse(eventId, fields));
    }

    private LiveRiskScoreResponse parse(String eventId, Map<String, String> fields) {
        String riskScoreRaw = fields.get(RiskScoreRedisKeys.FIELD_RISK_SCORE);
        String flaggedRaw = fields.get(RiskScoreRedisKeys.FIELD_FLAGGED);
        String reasonsRaw = fields.get(RiskScoreRedisKeys.FIELD_REASONS);
        String ruleVersion = fields.get(RiskScoreRedisKeys.FIELD_RULE_VERSION);
        String scoredAtRaw = fields.get(RiskScoreRedisKeys.FIELD_SCORED_AT);

        if (riskScoreRaw == null || flaggedRaw == null || ruleVersion == null || scoredAtRaw == null) {
            throw new IllegalStateException("Incomplete Redis hash for event_id=" + eventId);
        }

        int riskScore = Integer.parseInt(riskScoreRaw);
        boolean flagged = Boolean.parseBoolean(flaggedRaw);
        List<String> reasons = parseReasons(reasonsRaw);
        Instant scoredAt = Instant.parse(scoredAtRaw);

        return LiveRiskScoreResponse.fromRedis(eventId, riskScore, flagged, reasons, ruleVersion, scoredAt);
    }

    private List<String> parseReasons(String reasonsRaw) throws IllegalStateException {
        if (reasonsRaw == null || reasonsRaw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(reasonsRaw, REASONS_TYPE);
            return parsed != null ? List.copyOf(parsed) : List.of();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid reasons JSON in Redis hash", e);
        }
    }
}
