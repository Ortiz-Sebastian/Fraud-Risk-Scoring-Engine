package com.riskengine.engine.fraud;

import com.riskengine.common.model.RiskScore;
import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the IP burst detection pipeline using Flink's local execution environment.
 *
 * <p>Strategy: each test assembles a mini pipeline using {@code fromCollection} instead of Kafka,
 * and a {@link CapturingSink} instead of Cassandra. When the bounded collection source finishes,
 * Flink automatically emits {@code Watermark(Long.MAX_VALUE)}, which fires all pending
 * event-time windows deterministically — no sleeps or polling required.
 *
 * <p>Key behaviours verified:
 * <ul>
 *   <li>An IP with more distinct users than the threshold receives a flagged {@link RiskScore}</li>
 *   <li>An IP with users at or below the threshold produces no score</li>
 *   <li>Only the burst IP is flagged when multiple IPs are present</li>
 *   <li>Duplicate users on the same IP are correctly de-duplicated</li>
 *   <li>Every emitted score carries the correct field values (score, reasons, rule version)</li>
 * </ul>
 */
class IpBurstPipelineTest {

    private static final int TEST_THRESHOLD = 3;
    private static final List<RiskScore> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearCapture() {
        captured.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void emits_flagged_risk_score_when_ip_exceeds_distinct_user_threshold() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        String hotIp = "185.220.101.42";

        List<TransactionEvent> events = eventsFromDistinctUsers(hotIp, 6, base);

        runPipeline(events);

        assertThat(captured)
                .isNotEmpty()
                .allSatisfy(score -> {
                    assertThat(score.flagged()).isTrue();
                    assertThat(score.riskScore()).isEqualTo(80);
                    assertThat(score.reasons()).containsExactly("IP_BURST");
                    assertThat(score.ruleVersion()).isEqualTo("v1.0");
                    assertThat(score.eventId()).isNotBlank();
                });
    }

    @Test
    void emits_no_score_when_ip_has_users_at_or_below_threshold() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        String normalIp = "10.0.0.1";

        // 3 distinct users = exactly at threshold — should NOT trigger (> threshold required)
        List<TransactionEvent> events = eventsFromDistinctUsers(normalIp, TEST_THRESHOLD, base);

        runPipeline(events);

        assertThat(captured).isEmpty();
    }

    @Test
    void flags_only_the_burst_ip_when_multiple_ips_are_present() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");

        List<TransactionEvent> events = new ArrayList<>();
        events.addAll(eventsFromDistinctUsers("185.220.101.42", 6, base));  // above threshold
        events.addAll(eventsFromDistinctUsers("10.0.0.1", 2, base));        // below threshold

        runPipeline(events);

        assertThat(captured).isNotEmpty();
        assertThat(captured).allMatch(RiskScore::flagged);

        // All scored event IDs should belong to the hot IP's events
        assertThat(captured)
                .allSatisfy(score -> assertThat(score.eventId()).startsWith("185.220.101.42-"));
    }

    @Test
    void deduplicates_repeated_users_from_same_ip() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        String ip = "198.54.117.196";

        // 10 events but only 2 distinct users — far below threshold
        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String userId = (i % 2 == 0) ? "user-A" : "user-B";
            events.add(new TransactionEvent(
                    ip + "-" + i,
                    base.plusSeconds(i * 5L),
                    userId,
                    "merchant-1",
                    BigDecimal.valueOf(100.0),
                    "USD",
                    ip,
                    "device-" + i,
                    "US"
            ));
        }

        runPipeline(events);

        assertThat(captured).isEmpty();
    }

    @Test
    void risk_score_event_id_is_the_last_event_in_the_window() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        String hotIp = "104.244.72.115";

        List<TransactionEvent> events = eventsFromDistinctUsers(hotIp, 6, base);

        runPipeline(events);

        // The last event in the sequence has index 5
        assertThat(captured)
                .anySatisfy(score -> assertThat(score.eventId()).isEqualTo(hotIp + "-5"));
    }

    // -------------------------------------------------------------------------
    // Pipeline builder
    // -------------------------------------------------------------------------

    /**
     * Assembles and executes the IP burst detection sub-pipeline against a fixed set of events.
     * Uses the same operators as {@code StreamingJob} so the test mirrors production exactly,
     * except the threshold is lowered for test convenience.
     */
    private static void runPipeline(List<TransactionEvent> events) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        WatermarkStrategy<TransactionEvent> watermarks =
                WatermarkStrategy.<TransactionEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((event, ts) -> event.eventTs().toEpochMilli());

        env.fromCollection(events)
                .assignTimestampsAndWatermarks(watermarks)
                .keyBy(TransactionEvent::ip)
                .window(SlidingEventTimeWindows.of(Time.minutes(2), Time.seconds(30)))
                .aggregate(
                        new IpBurstDetector.DistinctUserAggregator(),
                        new IpBurstDetector.WindowEvaluator(TEST_THRESHOLD))
                .addSink(new CapturingSink())
                .name("Test Capturing Sink");

        env.execute("ip-burst-pipeline-test");
    }

    // -------------------------------------------------------------------------
    // Test data helpers
    // -------------------------------------------------------------------------

    /**
     * Generates events from {@code distinctUserCount} different users all sharing the same IP,
     * spaced 10 seconds apart. Event IDs follow the pattern {@code "<ip>-<index>"} so tests
     * can assert on the last event_id captured in a window.
     */
    private static List<TransactionEvent> eventsFromDistinctUsers(
            String ip, int distinctUserCount, Instant baseTime) {

        List<TransactionEvent> events = new ArrayList<>(distinctUserCount);
        for (int i = 0; i < distinctUserCount; i++) {
            events.add(new TransactionEvent(
                    ip + "-" + i,
                    baseTime.plusSeconds(i * 10L),
                    "user-" + i,
                    "merchant-1",
                    BigDecimal.valueOf(100.0),
                    "USD",
                    ip,
                    "device-" + i,
                    "US"
            ));
        }
        return events;
    }

    // -------------------------------------------------------------------------
    // Capturing sink
    // -------------------------------------------------------------------------

    static class CapturingSink extends RichSinkFunction<RiskScore> {

        @Override
        public void open(Configuration parameters) {
            // No resources to open — writing to an in-memory list.
        }

        @Override
        public void invoke(RiskScore score, Context context) {
            captured.add(score);
        }
    }
}
