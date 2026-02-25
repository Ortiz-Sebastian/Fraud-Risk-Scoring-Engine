package com.riskengine.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RiskScore(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("risk_score") int riskScore,
    boolean flagged,
    List<String> reasons,
    @JsonProperty("rule_version") String ruleVersion
) {}
