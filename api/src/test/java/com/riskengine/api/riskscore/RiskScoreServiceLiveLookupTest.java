package com.riskengine.api.riskscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.riskengine.api.persistence.RiskScoreEntity;
import com.riskengine.api.riskscore.redis.RiskScoreRedisRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskScoreServiceLiveLookupTest {

    @Mock
    private RiskScoreRepository repository;

    @Mock
    private RiskScoreRedisRepository redisRepository;

    @InjectMocks
    private RiskScoreService riskScoreService;

    @Test
    void findLiveByEventId_returnsRedisHit_withoutQueryingPostgres() {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        LiveRiskScoreResponse redisHit =
            LiveRiskScoreResponse.fromRedis("evt-1", 80, true, List.of("velocity"), "v1", scoredAt);
        when(redisRepository.findByEventId("evt-1")).thenReturn(Optional.of(redisHit));

        Optional<LiveRiskScoreLookupResult> result = riskScoreService.findLiveByEventId("evt-1");

        assertThat(result).isPresent();
        assertThat(result.get().response()).isEqualTo(redisHit);
        assertThat(result.get().response().source()).isEqualTo(LiveRiskScoreSource.REDIS);
        assertThat(result.get().cacheBypass()).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void findLiveByEventId_fallsBackToPostgres_onRedisMiss() {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        RiskScoreEntity row = entity("evt-2", 55, false, List.of("amount"), "v1", scoredAt);
        when(redisRepository.findByEventId("evt-2")).thenReturn(Optional.empty());
        when(repository.findById("evt-2")).thenReturn(Optional.of(row));

        Optional<LiveRiskScoreLookupResult> result = riskScoreService.findLiveByEventId("evt-2");

        assertThat(result).isPresent();
        assertThat(result.get().response().source()).isEqualTo(LiveRiskScoreSource.POSTGRES);
        assertThat(result.get().response().flagged()).isFalse();
        assertThat(result.get().cacheBypass()).isFalse();
    }

    @Test
    void findLiveByEventId_degradesToPostgres_whenRedisFails() {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        RiskScoreEntity row = entity("evt-3", 90, true, List.of("velocity"), "v1", scoredAt);
        when(redisRepository.findByEventId("evt-3")).thenThrow(new RuntimeException("connection refused"));
        when(repository.findById("evt-3")).thenReturn(Optional.of(row));

        Optional<LiveRiskScoreLookupResult> result = riskScoreService.findLiveByEventId("evt-3");

        assertThat(result).isPresent();
        assertThat(result.get().response().source()).isEqualTo(LiveRiskScoreSource.POSTGRES);
        assertThat(result.get().cacheBypass()).isTrue();
        verify(repository).findById("evt-3");
    }

    @Test
    void findLiveByEventId_returnsEmpty_whenBothStoresMiss() {
        when(redisRepository.findByEventId("missing")).thenReturn(Optional.empty());
        when(repository.findById("missing")).thenReturn(Optional.empty());

        Optional<LiveRiskScoreLookupResult> result = riskScoreService.findLiveByEventId("missing");

        assertThat(result).isEmpty();
    }

    private static RiskScoreEntity entity(
        String eventId,
        int riskScore,
        boolean flagged,
        List<String> reasons,
        String ruleVersion,
        Instant scoredAt
    ) {
        RiskScoreEntity entity = mock(RiskScoreEntity.class);
        when(entity.getEventId()).thenReturn(eventId);
        when(entity.getRiskScore()).thenReturn(riskScore);
        when(entity.isFlagged()).thenReturn(flagged);
        when(entity.getReasons()).thenReturn(reasons);
        when(entity.getRuleVersion()).thenReturn(ruleVersion);
        when(entity.getScoredAt()).thenReturn(scoredAt);
        return entity;
    }
}
