package com.riskengine.api.riskscore;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

    /**
     * Paginated list. Uses Spring {@link Pageable} ({@code page}, {@code size}, optional {@code sort}).
     */
    @GetMapping
    public PagedRiskScoresResponse list(
        @PageableDefault(size = 20, sort = "scoredAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return riskScoreService.list(pageable);
    }

    @GetMapping("/{eventId}")
    public RiskScoreResponse getByEventId(@PathVariable String eventId) {
        return riskScoreService
            .findByEventId(eventId)
            .orElseThrow(() -> new RiskScoreNotFoundException(eventId));
    }
}
