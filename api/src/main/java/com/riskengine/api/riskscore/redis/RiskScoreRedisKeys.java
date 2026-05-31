package com.riskengine.api.riskscore.redis;

/**
 * Redis key contract for flagged risk scores. Must stay aligned with Flink's
 * {@code RedisRiskScoreSink} ({@code fraud:flagged:<event_id>} hash with TTL).
 */
public final class RiskScoreRedisKeys {

    public static final String KEY_PREFIX = "fraud:flagged:";

    public static final String FIELD_RISK_SCORE = "risk_score";
    public static final String FIELD_FLAGGED = "flagged";
    public static final String FIELD_REASONS = "reasons";
    public static final String FIELD_RULE_VERSION = "rule_version";
    public static final String FIELD_SCORED_AT = "scored_at";

    private RiskScoreRedisKeys() {}

    public static String keyFor(String eventId) {
        return KEY_PREFIX + eventId;
    }
}
