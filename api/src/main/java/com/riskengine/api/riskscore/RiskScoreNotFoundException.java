package com.riskengine.api.riskscore;

public class RiskScoreNotFoundException extends RuntimeException {

    public RiskScoreNotFoundException(String eventId) {
        super("No risk score found for event_id=" + eventId);
    }
}
