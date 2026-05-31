package com.riskengine.api.riskscore;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Risk score payload for {@code GET /api/v1/risk-scores/{eventId}/live}, including lookup provenance.
 */
public record LiveRiskScoreResponse(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("risk_score") int riskScore,
    boolean flagged,
    List<String> reasons,
    @JsonProperty("rule_version") String ruleVersion,
    @JsonProperty("scored_at") Instant scoredAt,
    LiveRiskScoreSource source
) {
    public static LiveRiskScoreResponse fromPostgres(RiskScoreResponse pg) {
        return new LiveRiskScoreResponse(
            pg.eventId(),
            pg.riskScore(),
            pg.flagged(),
            pg.reasons(),
            pg.ruleVersion(),
            pg.scoredAt(),
            LiveRiskScoreSource.POSTGRES
        );
    }

    public static LiveRiskScoreResponse fromRedis(
        String eventId,
        int riskScore,
        boolean flagged,
        List<String> reasons,
        String ruleVersion,
        Instant scoredAt
    ) {
        return new LiveRiskScoreResponse(
            eventId,
            riskScore,
            flagged,
            reasons,
            ruleVersion,
            scoredAt,
            LiveRiskScoreSource.REDIS
        );
    }
}
