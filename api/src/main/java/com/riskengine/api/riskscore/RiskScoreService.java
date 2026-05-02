package com.riskengine.api.riskscore;

import java.util.Optional;
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
}
