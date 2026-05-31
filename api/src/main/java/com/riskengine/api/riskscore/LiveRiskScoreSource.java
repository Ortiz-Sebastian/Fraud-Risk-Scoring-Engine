package com.riskengine.api.riskscore;

/**
 * Indicates which store satisfied a live lookup. A miss is surfaced as HTTP 404, not in the body.
 */
public enum LiveRiskScoreSource {
    REDIS,
    POSTGRES
}
