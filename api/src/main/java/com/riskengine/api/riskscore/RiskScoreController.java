package com.riskengine.api.riskscore;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk-scores")
public class RiskScoreController {

    private final RiskScoreService riskScoreService;

    public RiskScoreController(RiskScoreService riskScoreService) {
        this.riskScoreService = riskScoreService;
    }

    @GetMapping("/{eventId}")
    public RiskScoreResponse getByEventId(@PathVariable String eventId) {
        return riskScoreService
            .findByEventId(eventId)
            .orElseThrow(() -> new RiskScoreNotFoundException(eventId));
    }
}
