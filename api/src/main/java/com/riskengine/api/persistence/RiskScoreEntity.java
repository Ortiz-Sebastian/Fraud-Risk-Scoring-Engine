package com.riskengine.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps to {@code risk_scores} as created by the Flink {@code PostgresRiskScoreSink} (risk-engine module).
 * Columns: {@code event_id}, {@code risk_score}, {@code flagged}, {@code reasons}, {@code rule_version},
 * {@code scored_at}.
 */
@Entity
@Table(name = "risk_scores")
public class RiskScoreEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reasons", columnDefinition = "text[]", nullable = false)
    private List<String> reasons;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "scored_at", nullable = false)
    private Instant scoredAt;

    protected RiskScoreEntity() {}

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(int riskScore) {
        this.riskScore = riskScore;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public Instant getScoredAt() {
        return scoredAt;
    }

    public void setScoredAt(Instant scoredAt) {
        this.scoredAt = scoredAt;
    }
}
