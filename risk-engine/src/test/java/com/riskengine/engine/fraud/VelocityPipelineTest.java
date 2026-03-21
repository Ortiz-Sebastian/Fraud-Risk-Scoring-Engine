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
 * Integration tests for the velocity detection pipeline using Flink's local execution environment.
 *
 * <p>Strategy: each test assembles a mini pipeline using {@code fromCollection} instead of Kafka,
 * and a {@link CapturingSink} instead of Cassandra. When the bounded collection source finishes,
 * Flink automatically emits {@code Watermark(Long.MAX_VALUE)}, which fires all pending
 * event-time windows deterministically — no sleeps or polling required.
 *
 * <p>Key behaviours verified:
 * <ul>
 *   <li>A user whose transaction count exceeds the threshold receives a flagged {@link RiskScore}</li>
 *   <li>A user below the threshold produces no score</li>
 *   <li>Only the high-velocity user is flagged when multiple users share the same window</li>
 *   <li>Every emitted score carries the correct field values (score, reasons, rule version)</li>
 * </ul>
 */
class VelocityPipelineTest {

    /**
     * Static store shared between the test method and the Flink task that runs the sink.
     * {@link CopyOnWriteArrayList} is used because Flink may run the sink in a different thread
     * even in local mode.
     */
    private static final List<RiskScore> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearCapture() {
        captured.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void emits_flagged_risk_score_when_user_exceeds_velocity_threshold() throws Exception {
        // 15 events in ~2.5 minutes — well above the default threshold of 10
        List<TransactionEvent> events = eventsForUser("user-attack", 15, Instant.parse("2024-01-01T10:00:00Z"));

        runPipeline(events);

        assertThat(captured)
                .isNotEmpty()
                .allSatisfy(score -> {
                    assertThat(score.flagged()).isTrue();
                    assertThat(score.riskScore()).isEqualTo(75);
                    assertThat(score.reasons()).containsExactly("USER_VELOCITY");
                    assertThat(score.ruleVersion()).isEqualTo("v1.0");
                    assertThat(score.eventId()).isNotBlank();
                });
    }

    @Test
    void emits_no_score_when_user_is_below_velocity_threshold() throws Exception {
        // 5 events — below the default threshold of 10
        List<TransactionEvent> events = eventsForUser("user-normal", 5, Instant.parse("2024-01-01T10:00:00Z"));

        runPipeline(events);

        assertThat(captured).isEmpty();
    }

    @Test
    void flags_only_the_high_velocity_user_when_multiple_users_are_present() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");

        List<TransactionEvent> events = new ArrayList<>();
        events.addAll(eventsForUser("user-attack", 15, base));   // above threshold
        events.addAll(eventsForUser("user-normal", 3, base));    // below threshold

        runPipeline(events);

        assertThat(captured).isNotEmpty();
        assertThat(captured).allMatch(RiskScore::flagged);

        // All scored event IDs must belong to the high-velocity user
        assertThat(captured)
                .allSatisfy(score -> assertThat(score.eventId()).startsWith("user-attack-"));
    }

    @Test
    void risk_score_event_id_is_the_last_event_in_the_window() throws Exception {
        // Events are added in chronological order; the last event_id is "user-burst-14"
        List<TransactionEvent> events = eventsForUser("user-burst", 15, Instant.parse("2024-01-01T10:00:00Z"));

        runPipeline(events);

        // At least one window should capture the final event in the sequence
        assertThat(captured)
                .anySatisfy(score -> assertThat(score.eventId()).isEqualTo("user-burst-14"));
    }

    // -------------------------------------------------------------------------
    // Pipeline builder
    // -------------------------------------------------------------------------

    /**
     * Assembles and executes the velocity detection sub-pipeline against a fixed set of events.
     * Uses the same operators as {@code StreamingJob} so the test mirrors production exactly.
     */
    private static void runPipeline(List<TransactionEvent> events) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        WatermarkStrategy<TransactionEvent> watermarks =
                WatermarkStrategy.<TransactionEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((event, ts) -> event.eventTs().toEpochMilli());

        env.fromCollection(events)
                .assignTimestampsAndWatermarks(watermarks)
                .keyBy(TransactionEvent::userId)
                .window(SlidingEventTimeWindows.of(Time.minutes(5), Time.minutes(1)))
                .aggregate(new VelocityDetector.CountAggregator(), new VelocityDetector.WindowEvaluator())
                .addSink(new CapturingSink())
                .name("Test Capturing Sink");

        env.execute("velocity-pipeline-test");
    }

    // -------------------------------------------------------------------------
    // Test data helpers
    // -------------------------------------------------------------------------

    /**
     * Generates {@code count} events for the given user, spaced 10 seconds apart starting
     * from {@code baseTime}. Event IDs follow the pattern {@code "<userId>-<index>"} so tests
     * can assert on the last event_id captured in a window.
     */
    private static List<TransactionEvent> eventsForUser(String userId, int count, Instant baseTime) {
        List<TransactionEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(new TransactionEvent(
                    userId + "-" + i,
                    baseTime.plusSeconds(i * 10L),
                    userId,
                    "merchant-1",
                    BigDecimal.valueOf(100.0),
                    "USD",
                    "10.0.0.1",
                    "device-1",
                    "US"
            ));
        }
        return events;
    }

    // -------------------------------------------------------------------------
    // Capturing sink
    // -------------------------------------------------------------------------

    /**
     * Flink sink that writes {@link RiskScore} records into a static list so tests can
     * assert on pipeline output after {@code env.execute()} returns.
     *
     * <p>The list is static because Flink serializes and deserializes the sink function
     * when moving it to a task thread — an instance field would not be reachable from the
     * original test method. {@link CopyOnWriteArrayList} provides thread-safe access without
     * external synchronization.
     */
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
