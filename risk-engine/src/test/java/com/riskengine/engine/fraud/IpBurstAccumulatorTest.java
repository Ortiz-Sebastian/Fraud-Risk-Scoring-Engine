package com.riskengine.engine.fraud;

import com.riskengine.common.model.TransactionEvent;
import com.riskengine.engine.fraud.IpBurstDetector.DistinctUserAggregator;
import com.riskengine.engine.fraud.IpBurstDetector.IpBurstAccumulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link IpBurstDetector.DistinctUserAggregator}.
 *
 * No Flink runtime or infrastructure is needed — the aggregator is tested as plain Java.
 * These tests verify the distinct-user accumulation logic in isolation before the pipeline
 * tests exercise the full windowing behaviour.
 */
class IpBurstAccumulatorTest {

    private DistinctUserAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new DistinctUserAggregator();
    }

    @Test
    void createAccumulator_starts_with_empty_set_and_empty_event_id() {
        IpBurstAccumulator acc = aggregator.createAccumulator();

        assertThat(acc.distinctUserIds).isEmpty();
        assertThat(acc.lastEventId).isEmpty();
    }

    @Test
    void add_collects_distinct_user_ids() {
        IpBurstAccumulator acc = aggregator.createAccumulator();

        aggregator.add(event("e1", "user-A"), acc);
        aggregator.add(event("e2", "user-B"), acc);
        aggregator.add(event("e3", "user-C"), acc);

        assertThat(acc.distinctUserIds).containsExactlyInAnyOrder("user-A", "user-B", "user-C");
    }

    @Test
    void add_deduplicates_repeated_user_ids() {
        IpBurstAccumulator acc = aggregator.createAccumulator();

        aggregator.add(event("e1", "user-A"), acc);
        aggregator.add(event("e2", "user-A"), acc);
        aggregator.add(event("e3", "user-B"), acc);
        aggregator.add(event("e4", "user-B"), acc);

        assertThat(acc.distinctUserIds).containsExactlyInAnyOrder("user-A", "user-B");
    }

    @Test
    void add_tracks_the_most_recently_added_event_id() {
        IpBurstAccumulator acc = aggregator.createAccumulator();

        aggregator.add(event("first", "user-A"), acc);
        aggregator.add(event("second", "user-B"), acc);
        aggregator.add(event("third", "user-C"), acc);

        assertThat(acc.lastEventId).isEqualTo("third");
    }

    @Test
    void getResult_returns_the_same_accumulator_instance() {
        IpBurstAccumulator acc = aggregator.createAccumulator();
        aggregator.add(event("e1", "user-A"), acc);

        IpBurstAccumulator result = aggregator.getResult(acc);

        assertThat(result).isSameAs(acc);
    }

    @Test
    void merge_unions_distinct_user_sets() {
        IpBurstAccumulator a = accumulator(Set.of("user-A", "user-B"), "e-aaa");
        IpBurstAccumulator b = accumulator(Set.of("user-B", "user-C", "user-D"), "e-bbb");

        IpBurstAccumulator merged = aggregator.merge(a, b);

        assertThat(merged.distinctUserIds).containsExactlyInAnyOrder("user-A", "user-B", "user-C", "user-D");
    }

    @Test
    void merge_keeps_lexically_greater_event_id() {
        IpBurstAccumulator a = accumulator(Set.of("user-A"), "e-aaa");
        IpBurstAccumulator b = accumulator(Set.of("user-B"), "e-zzz");

        IpBurstAccumulator merged = aggregator.merge(a, b);

        assertThat(merged.lastEventId).isEqualTo("e-zzz");
    }

    @Test
    void merge_keeps_a_event_id_when_it_is_lexically_greater() {
        IpBurstAccumulator a = accumulator(Set.of("user-A"), "e-zzz");
        IpBurstAccumulator b = accumulator(Set.of("user-B"), "e-aaa");

        IpBurstAccumulator merged = aggregator.merge(a, b);

        assertThat(merged.lastEventId).isEqualTo("e-zzz");
    }

    @Test
    void merge_returns_a_as_the_result_accumulator() {
        IpBurstAccumulator a = accumulator(Set.of("user-A"), "e-aaa");
        IpBurstAccumulator b = accumulator(Set.of("user-B"), "e-bbb");

        IpBurstAccumulator result = aggregator.merge(a, b);

        assertThat(result).isSameAs(a);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TransactionEvent event(String eventId, String userId) {
        return new TransactionEvent(
                eventId,
                Instant.now(),
                userId,
                "merchant-1",
                BigDecimal.valueOf(50.0),
                "USD",
                "185.220.101.42",
                "device-1",
                "US"
        );
    }

    private static IpBurstAccumulator accumulator(Set<String> userIds, String lastEventId) {
        IpBurstAccumulator acc = new IpBurstAccumulator();
        acc.distinctUserIds.addAll(userIds);
        acc.lastEventId = lastEventId;
        return acc;
    }
}
