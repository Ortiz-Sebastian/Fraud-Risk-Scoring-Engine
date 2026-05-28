package com.riskengine.api.riskscore;

import com.riskengine.api.persistence.RiskScoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RiskScoreRepository
    extends JpaRepository<RiskScoreEntity, String>, JpaSpecificationExecutor<RiskScoreEntity> {}
