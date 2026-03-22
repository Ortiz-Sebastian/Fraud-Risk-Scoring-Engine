package com.riskengine.engine.fraud;

import com.riskengine.common.model.RiskScore;
import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
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
 * Integration tests for the device profiling pipeline using Flink's local execution environment.
 *
 * <p>Strategy: each test assembles a mini pipeline using {@code fromCollection} instead of Kafka,
 * and a {@link CapturingSink} instead of Cassandra. The {@link DeviceProfileDetector.NewDeviceFunction}
 * is exercised end-to-end with real Flink state management.
 *
 * <p>Key behaviours verified:
 * <ul>
 *   <li>A new device whose first transaction exceeds the threshold is flagged</li>
 *   <li>A new device with a below-threshold amount is not flagged</li>
 *   <li>A known device making a high-value purchase is not flagged (device already seen)</li>
 *   <li>Only new high-value devices are flagged when mixed traffic is present</li>
 *   <li>Amount exactly at the threshold triggers the flag (>= comparison)</li>
 *   <li>Every emitted score carries the correct field values (score, reasons, rule version)</li>
 * </ul>
 */
class DeviceProfilePipelineTest {

    private static final BigDecimal TEST_THRESHOLD = BigDecimal.valueOf(500);
    private static final long TEST_RETENTION_MINUTES = 60;
    private static final List<RiskScore> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearCapture() {
        captured.clear();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void emits_flagged_risk_score_when_new_device_makes_high_value_purchase() throws Exception {
        List<TransactionEvent> events = List.of(
                event("evt-1", "device-new-1", BigDecimal.valueOf(750))
        );

        runPipeline(events);

        assertThat(captured)
                .hasSize(1)
                .allSatisfy(score -> {
                    assertThat(score.flagged()).isTrue();
                    assertThat(score.riskScore()).isEqualTo(85);
                    assertThat(score.reasons()).containsExactly("NEW_DEVICE_HIGH_VALUE");
                    assertThat(score.ruleVersion()).isEqualTo("v1.0");
                    assertThat(score.eventId()).isEqualTo("evt-1");
                });
    }

    @Test
    void emits_no_score_when_new_device_amount_is_below_threshold() throws Exception {
        List<TransactionEvent> events = List.of(
                event("evt-1", "device-low-1", BigDecimal.valueOf(100)),
                event("evt-2", "device-low-2", BigDecimal.valueOf(499.99))
        );

        runPipeline(events);

        assertThat(captured).isEmpty();
    }

    @Test
    void emits_flag_when_amount_is_exactly_at_threshold() throws Exception {
        List<TransactionEvent> events = List.of(
                event("evt-1", "device-exact", BigDecimal.valueOf(500))
        );

        runPipeline(events);

        assertThat(captured)
                .hasSize(1)
                .allSatisfy(score -> assertThat(score.eventId()).isEqualTo("evt-1"));
    }

    @Test
    void does_not_flag_known_device_even_with_high_value_purchase() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");

        List<TransactionEvent> events = List.of(
                new TransactionEvent("evt-1", base, "user-1", "merchant-1",
                        BigDecimal.valueOf(50), "USD", "10.0.0.1", "device-repeat", "US"),
                new TransactionEvent("evt-2", base.plusSeconds(30), "user-1", "merchant-1",
                        BigDecimal.valueOf(2000), "USD", "10.0.0.1", "device-repeat", "US")
        );

        runPipeline(events);

        assertThat(captured).isEmpty();
    }

    @Test
    void flags_only_new_high_value_devices_in_mixed_traffic() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");

        List<TransactionEvent> events = new ArrayList<>();
        // New device, low amount — should NOT flag
        events.add(new TransactionEvent("evt-low", base, "user-1", "merchant-1",
                BigDecimal.valueOf(25), "USD", "10.0.0.1", "device-A", "US"));
        // New device, high amount — SHOULD flag
        events.add(new TransactionEvent("evt-high", base.plusSeconds(10), "user-2", "merchant-1",
                BigDecimal.valueOf(1500), "USD", "10.0.0.2", "device-B", "US"));
        // Known device (device-A), high amount — should NOT flag
        events.add(new TransactionEvent("evt-known", base.plusSeconds(20), "user-1", "merchant-1",
                BigDecimal.valueOf(900), "USD", "10.0.0.1", "device-A", "US"));
        // New device, high amount — SHOULD flag
        events.add(new TransactionEvent("evt-high-2", base.plusSeconds(30), "user-3", "merchant-1",
                BigDecimal.valueOf(600), "USD", "10.0.0.3", "device-C", "US"));

        runPipeline(events);

        assertThat(captured).hasSize(2);
        assertThat(captured)
                .extracting(RiskScore::eventId)
                .containsExactlyInAnyOrder("evt-high", "evt-high-2");
        assertThat(captured).allSatisfy(score -> {
            assertThat(score.flagged()).isTrue();
            assertThat(score.riskScore()).isEqualTo(85);
            assertThat(score.reasons()).containsExactly("NEW_DEVICE_HIGH_VALUE");
        });
    }

    @Test
    void multiple_new_devices_in_parallel_are_each_evaluated_independently() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");

        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(new TransactionEvent(
                    "evt-" + i,
                    base.plusSeconds(i * 5L),
                    "user-" + i,
                    "merchant-1",
                    BigDecimal.valueOf(800),
                    "USD",
                    "10.0.0." + i,
                    "device-unique-" + i,
                    "US"
            ));
        }

        runPipeline(events);

        assertThat(captured).hasSize(5);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            assertThat(captured)
                    .anySatisfy(score -> assertThat(score.eventId()).isEqualTo("evt-" + idx));
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline builder
    // -------------------------------------------------------------------------

    /**
     * Assembles and executes the device profiling sub-pipeline against a fixed set of events.
     * Uses the same operator as {@code StreamingJob} so the test mirrors production exactly,
     * except the threshold and retention are set explicitly for test isolation.
     */
    private static void runPipeline(List<TransactionEvent> events) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        WatermarkStrategy<TransactionEvent> watermarks =
                WatermarkStrategy.<TransactionEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((event, ts) -> event.eventTs().toEpochMilli());

        env.fromCollection(events)
                .assignTimestampsAndWatermarks(watermarks)
                .keyBy(TransactionEvent::deviceId)
                .process(new DeviceProfileDetector.NewDeviceFunction(TEST_THRESHOLD, TEST_RETENTION_MINUTES))
                .addSink(new CapturingSink())
                .name("Test Capturing Sink");

        env.execute("device-profile-pipeline-test");
    }

    // -------------------------------------------------------------------------
    // Test data helpers
    // -------------------------------------------------------------------------

    private static TransactionEvent event(String eventId, String deviceId, BigDecimal amount) {
        return new TransactionEvent(
                eventId,
                Instant.parse("2024-01-01T10:00:00Z"),
                "user-1",
                "merchant-1",
                amount,
                "USD",
                "10.0.0.1",
                deviceId,
                "US"
        );
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
