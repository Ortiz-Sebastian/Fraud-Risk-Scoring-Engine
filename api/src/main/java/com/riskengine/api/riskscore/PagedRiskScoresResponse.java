package com.riskengine.api.riskscore;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.riskengine.api.persistence.RiskScoreEntity;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Offset/limit pagination over {@link RiskScoreResponse} rows (via Spring {@link Page}).
 */
public record PagedRiskScoresResponse(
    @JsonProperty("items") List<RiskScoreResponse> items,
    /** Zero-based page index. */
    @JsonProperty("page") int page,
    @JsonProperty("page_size") int pageSize,
    @JsonProperty("total_elements") long totalElements,
    @JsonProperty("total_pages") int totalPages,
    @JsonProperty("first") boolean first,
    @JsonProperty("last") boolean last
) {
    public static PagedRiskScoresResponse from(Page<RiskScoreEntity> page) {
        List<RiskScoreResponse> items = page.getContent().stream().map(RiskScoreResponse::from).toList();
        return new PagedRiskScoresResponse(
            items,
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isFirst(),
            page.isLast()
        );
    }
}
