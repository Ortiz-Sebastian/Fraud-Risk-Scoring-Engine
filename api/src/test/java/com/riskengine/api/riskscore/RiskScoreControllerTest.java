package com.riskengine.api.riskscore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = RiskScoreController.class)
class RiskScoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskScoreService riskScoreService;

    @Test
    void getByEventId_returnsBody_whenPresent() throws Exception {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        when(riskScoreService.findByEventId(eq("evt-1")))
            .thenReturn(
                Optional.of(
                    new RiskScoreResponse("evt-1", 42, true, List.of("velocity"), "v1", scoredAt)
                )
            );

        mockMvc
            .perform(get("/api/v1/risk-scores/evt-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_id").value("evt-1"))
            .andExpect(jsonPath("$.risk_score").value(42))
            .andExpect(jsonPath("$.flagged").value(true))
            .andExpect(jsonPath("$.reasons[0]").value("velocity"))
            .andExpect(jsonPath("$.rule_version").value("v1"))
            .andExpect(jsonPath("$.scored_at").value("2026-05-01T12:00:00Z"));
    }

    @Test
    void list_returnsPagedBody_withDefaults() throws Exception {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        PagedRiskScoresResponse page =
            new PagedRiskScoresResponse(
                List.of(new RiskScoreResponse("evt-1", 42, true, List.of("velocity"), "v1", scoredAt)),
                0,
                20,
                1,
                1,
                true,
                true
            );
        when(riskScoreService.list(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(page);

        mockMvc
            .perform(get("/api/v1/risk-scores"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].event_id").value("evt-1"))
            .andExpect(jsonPath("$.items[0].scored_at").value("2026-05-01T12:00:00Z"))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.page_size").value(20))
            .andExpect(jsonPath("$.total_elements").value(1))
            .andExpect(jsonPath("$.total_pages").value(1))
            .andExpect(jsonPath("$.first").value(true))
            .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void list_returns400_whenInvalidRange() throws Exception {
        when(riskScoreService.list(any(), any(), any(), isNull(), isNull(), isNull(), isNull()))
            .thenThrow(new IllegalArgumentException("Invalid scored_at range: 'from' must be before or equal to 'to'"));

        mockMvc
            .perform(get("/api/v1/risk-scores?from=2026-05-02T00:00:00Z&to=2026-05-01T00:00:00Z"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void getLiveByEventId_returnsRedisSource_whenPresent() throws Exception {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        LiveRiskScoreResponse body =
            LiveRiskScoreResponse.fromRedis("evt-live", 77, true, List.of("velocity"), "v1", scoredAt);
        when(riskScoreService.findLiveByEventId(eq("evt-live")))
            .thenReturn(Optional.of(new LiveRiskScoreLookupResult(body, false)));

        mockMvc
            .perform(get("/api/v1/risk-scores/evt-live/live"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_id").value("evt-live"))
            .andExpect(jsonPath("$.risk_score").value(77))
            .andExpect(jsonPath("$.source").value("REDIS"))
            .andExpect(header().doesNotExist("X-Cache-Status"));
    }

    @Test
    void getLiveByEventId_returnsPostgresSource_withBypassHeader_whenRedisDown() throws Exception {
        Instant scoredAt = Instant.parse("2026-05-01T12:00:00Z");
        LiveRiskScoreResponse body =
            LiveRiskScoreResponse.fromPostgres(
                new RiskScoreResponse("evt-pg", 60, true, List.of("amount"), "v1", scoredAt)
            );
        when(riskScoreService.findLiveByEventId(eq("evt-pg")))
            .thenReturn(Optional.of(new LiveRiskScoreLookupResult(body, true)));

        mockMvc
            .perform(get("/api/v1/risk-scores/evt-pg/live"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("POSTGRES"))
            .andExpect(header().string("X-Cache-Status", "BYPASS"));
    }

    @Test
    void getLiveByEventId_returns404_whenMissing() throws Exception {
        when(riskScoreService.findLiveByEventId(eq("missing-live"))).thenReturn(Optional.empty());

        mockMvc
            .perform(get("/api/v1/risk-scores/missing-live/live"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void getByEventId_returns404_whenMissing() throws Exception {
        when(riskScoreService.findByEventId(eq("missing"))).thenReturn(Optional.empty());

        mockMvc
            .perform(get("/api/v1/risk-scores/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }
}
