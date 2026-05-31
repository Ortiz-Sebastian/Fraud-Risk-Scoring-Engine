package com.riskengine.api.riskscore;

/**
 * Outcome of a live lookup, including whether Redis was bypassed due to unavailability.
 */
public record LiveRiskScoreLookupResult(LiveRiskScoreResponse response, boolean cacheBypass) {}
