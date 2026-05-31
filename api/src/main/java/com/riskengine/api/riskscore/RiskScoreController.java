package com.riskengine.api.riskscore;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
     * Paginated list with optional filters and sorting.
     */
    @GetMapping
    public PagedRiskScoresResponse list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) Boolean flagged,
        @RequestParam(required = false) Integer minScore,
        @RequestParam(required = false) Integer maxScore,
        @RequestParam(required = false) List<String> reason,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = "desc") String order
    ) {
        Sort.Direction direction = Sort.Direction.fromString(order);
        PageRequest pageable = PageRequest.of(page, size, buildSort(sort, direction));
        return riskScoreService.list(pageable, from, to, flagged, minScore, maxScore, reason);
    }

    @GetMapping("/{eventId}/live")
    public ResponseEntity<LiveRiskScoreResponse> getLiveByEventId(@PathVariable String eventId) {
        LiveRiskScoreLookupResult result =
            riskScoreService
                .findLiveByEventId(eventId)
                .orElseThrow(() -> new RiskScoreNotFoundException(eventId));

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.cacheBypass()) {
            response.header("X-Cache-Status", "BYPASS");
        }
        return response.body(result.response());
    }

    @GetMapping("/{eventId}")
    public RiskScoreResponse getByEventId(@PathVariable String eventId) {
        return riskScoreService
            .findByEventId(eventId)
            .orElseThrow(() -> new RiskScoreNotFoundException(eventId));
    }

    private Sort buildSort(String sort, Sort.Direction direction) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(direction, "scoredAt");
        }
        String normalized = sort.trim();
        if (!normalized.equals("scoredAt") && !normalized.equals("riskScore")) {
            throw new IllegalArgumentException("Unsupported sort field: " + sort);
        }
        return Sort.by(direction, normalized);
    }
}
