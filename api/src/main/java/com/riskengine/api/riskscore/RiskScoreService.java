package com.riskengine.api.riskscore;

import com.riskengine.api.persistence.RiskScoreEntity;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    public PagedRiskScoresResponse list(
        Pageable pageable,
        Instant from,
        Instant to,
        Boolean flagged,
        Integer minScore,
        Integer maxScore,
        List<String> reasons
    ) {
        validateRanges(from, to, minScore, maxScore);
        Pageable effective =
            pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "scoredAt"));
        Specification<RiskScoreEntity> spec = buildSpecification(from, to, flagged, minScore, maxScore, reasons);
        Page<RiskScoreEntity> page = repository.findAll(spec, effective);
        return PagedRiskScoresResponse.from(page);
    }

    private void validateRanges(Instant from, Instant to, Integer minScore, Integer maxScore) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Invalid scored_at range: 'from' must be before or equal to 'to'");
        }
        if (minScore != null && maxScore != null && minScore > maxScore) {
            throw new IllegalArgumentException("Invalid score range: 'minScore' must be <= 'maxScore'");
        }
    }

    private Specification<RiskScoreEntity> buildSpecification(
        Instant from,
        Instant to,
        Boolean flagged,
        Integer minScore,
        Integer maxScore,
        List<String> reasons
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scoredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("scoredAt"), to));
            }
            if (flagged != null) {
                predicates.add(cb.equal(root.get("flagged"), flagged));
            }
            if (minScore != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("riskScore"), minScore));
            }
            if (maxScore != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("riskScore"), maxScore));
            }
            if (reasons != null && !reasons.isEmpty()) {
                for (String reason : reasons) {
                    if (reason == null || reason.isBlank()) {
                        continue;
                    }
                    predicates.add(
                        cb.isNotNull(cb.function("array_position", Integer.class, root.get("reasons"), cb.literal(reason)))
                    );
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
