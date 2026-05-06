package com.riskengine.api.riskscore;

import com.riskengine.api.persistence.RiskScoreEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskScoreService {

    private final RiskScoreRepository repository;

    public RiskScoreService(RiskScoreRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<RiskScoreResponse> findByEventId(String eventId) {
        return repository.findById(eventId).map(RiskScoreResponse::from);
    }

    /**
     * Lists scores with offset/limit pagination ({@code page} + {@code size}).
     * Default order is {@code scored_at} descending when the client does not send {@code sort}.
     */
    @Transactional(readOnly = true)
    public PagedRiskScoresResponse list(Pageable pageable) {
        Pageable effective =
            pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "scoredAt"));
        Page<RiskScoreEntity> page = repository.findAll(effective);
        return PagedRiskScoresResponse.from(page);
    }
}
