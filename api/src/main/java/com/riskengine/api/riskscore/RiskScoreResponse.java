package com.riskengine.api.riskscore;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.riskengine.api.persistence.RiskScoreEntity;
import java.time.Instant;
import java.util.List;

/**
 * HTTP representation of a persisted risk score. Aligns with {@link RiskScoreEntity} /
 * {@code risk_scores} (same fields as {@link com.riskengine.common.model.RiskScore} plus
 * {@code scored_at} from the sink).
 */
public record RiskScoreResponse(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("risk_score") int riskScore,
    boolean flagged,
    List<String> reasons,
    @JsonProperty("rule_version") String ruleVersion,
    @JsonProperty("scored_at") Instant scoredAt
) {
    public static RiskScoreResponse from(RiskScoreEntity entity) {
        List<String> r = entity.getReasons();
        return new RiskScoreResponse(
            entity.getEventId(),
            entity.getRiskScore(),
            entity.isFlagged(),
            r != null ? List.copyOf(r) : List.of(),
            entity.getRuleVersion(),
            entity.getScoredAt()
        );
    }
}
