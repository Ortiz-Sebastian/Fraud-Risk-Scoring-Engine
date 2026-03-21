package com.riskengine.engine.fraud;

import com.riskengine.common.model.TransactionEvent;
import com.riskengine.engine.fraud.VelocityDetector.CountAggregator;
import com.riskengine.engine.fraud.VelocityDetector.VelocityAccumulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link VelocityDetector.CountAggregator}.
 *
 * No Flink runtime or infrastructure is needed — the aggregator is tested as plain Java.
 * These tests verify the accumulation logic in isolation before the pipeline tests
 * exercise the full windowing behaviour.
 */
class VelocityCountAggregatorTest {

    private CountAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new CountAggregator();
    }

    @Test
    void createAccumulator_starts_at_zero_with_empty_event_id() {
        VelocityAccumulator acc = aggregator.createAccumulator();

        assertThat(acc.count).isZero();
        assertThat(acc.lastEventId).isEmpty();
    }

    @Test
    void add_increments_count_by_one_per_event() {
        VelocityAccumulator acc = aggregator.createAccumulator();

        aggregator.add(event("e1"), acc);
        aggregator.add(event("e2"), acc);
        aggregator.add(event("e3"), acc);

        assertThat(acc.count).isEqualTo(3);
    }

    @Test
    void add_tracks_the_most_recently_added_event_id() {
        VelocityAccumulator acc = aggregator.createAccumulator();

        aggregator.add(event("first"), acc);
        aggregator.add(event("second"), acc);
        aggregator.add(event("third"), acc);

        assertThat(acc.lastEventId).isEqualTo("third");
    }

    @Test
    void getResult_returns_the_same_accumulator_instance() {
        VelocityAccumulator acc = aggregator.createAccumulator();
        aggregator.add(event("e1"), acc);

        VelocityAccumulator result = aggregator.getResult(acc);

        assertThat(result).isSameAs(acc);
    }

    @Test
    void merge_sums_counts_from_both_accumulators() {
        VelocityAccumulator a = accumulator(4, "e-aaa");
        VelocityAccumulator b = accumulator(7, "e-bbb");

        VelocityAccumulator merged = aggregator.merge(a, b);

        assertThat(merged.count).isEqualTo(11);
    }

    @Test
    void merge_keeps_lexically_greater_event_id() {
        // "e-zzz" > "e-aaa" lexically — merged result should keep "e-zzz"
        VelocityAccumulator a = accumulator(3, "e-aaa");
        VelocityAccumulator b = accumulator(2, "e-zzz");

        VelocityAccumulator merged = aggregator.merge(a, b);

        assertThat(merged.lastEventId).isEqualTo("e-zzz");
    }

    @Test
    void merge_keeps_a_event_id_when_it_is_lexically_greater() {
        VelocityAccumulator a = accumulator(3, "e-zzz");
        VelocityAccumulator b = accumulator(2, "e-aaa");

        VelocityAccumulator merged = aggregator.merge(a, b);

        assertThat(merged.lastEventId).isEqualTo("e-zzz");
    }

    @Test
    void merge_returns_a_as_the_result_accumulator() {
        VelocityAccumulator a = accumulator(3, "e-aaa");
        VelocityAccumulator b = accumulator(2, "e-bbb");

        VelocityAccumulator result = aggregator.merge(a, b);

        assertThat(result).isSameAs(a);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TransactionEvent event(String eventId) {
        return new TransactionEvent(
                eventId,
                Instant.now(),
                "user-1",
                "merchant-1",
                BigDecimal.valueOf(50.0),
                "USD",
                "10.0.0.1",
                "device-1",
                "US"
        );
    }

    private static VelocityAccumulator accumulator(int count, String lastEventId) {
        VelocityAccumulator acc = new VelocityAccumulator();
        acc.count = count;
        acc.lastEventId = lastEventId;
        return acc;
    }
}
