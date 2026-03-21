package com.riskengine.engine.fraud;

import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Velocity detection rule: flag a user_id that submits more than THRESHOLD
 * transactions within a 5-minute sliding window (slide every 1 minute).
 *
 * <p>Uses Flink's two-stage aggregate pattern:
 * <ol>
 *   <li>{@link CountAggregator} — accumulates a count and the last event_id as each record
 *       arrives. State stays O(1) regardless of how many events land in the window.</li>
 *   <li>{@link WindowEvaluator} — receives the finished accumulator once per window close,
 *       applies the threshold check, and emits a {@link RiskScore} if triggered.</li>
 * </ol>
 *
 * <p>Threshold is read from {@code AppConfig.velocityThreshold()} (env: {@code VELOCITY_THRESHOLD},
 * default 10) so it can be tuned without a code change.
 */
public final class VelocityDetector {

    static final String RULE_VERSION = "v1.0";

    private VelocityDetector() {}

    // -------------------------------------------------------------------------
    // Stage 1: per-record accumulation
    // -------------------------------------------------------------------------

    /**
     * Accumulates a transaction count and the most-recently-seen event_id within
     * a window. This is the only state held per window slot — intentionally minimal.
     */
    public static final class VelocityAccumulator {
        public int count = 0;
        public String lastEventId = "";
    }

    /**
     * Flink {@link AggregateFunction} that increments a counter and tracks the
     * last event_id for each record in the window.
     *
     * <p>{@code merge()} is implemented correctly for completeness, though Flink only
     * invokes it for session windows. Sliding windows never merge accumulators.
     */
    public static final class CountAggregator
            implements AggregateFunction<TransactionEvent, VelocityAccumulator, VelocityAccumulator> {

        @Override
        public VelocityAccumulator createAccumulator() {
            return new VelocityAccumulator();
        }

        @Override
        public VelocityAccumulator add(TransactionEvent event, VelocityAccumulator acc) {
            acc.count++;
            acc.lastEventId = event.eventId();
            return acc;
        }

        @Override
        public VelocityAccumulator getResult(VelocityAccumulator acc) {
            return acc;
        }

        @Override
        public VelocityAccumulator merge(VelocityAccumulator a, VelocityAccumulator b) {
            a.count += b.count;
            if (b.lastEventId.compareTo(a.lastEventId) > 0) {
                a.lastEventId = b.lastEventId;
            }
            return a;
        }
    }

    // -------------------------------------------------------------------------
    // Stage 2: threshold evaluation after window closes
    // -------------------------------------------------------------------------

    /**
     * Flink {@link ProcessWindowFunction} that receives the finished
     * {@link VelocityAccumulator} for a closed window, compares the count against
     * the configured threshold, and emits a {@link RiskScore} when exceeded.
     *
     * <p>The threshold is captured at construction time so it is read once per
     * Flink subtask lifecycle rather than on every window close.
     */
    public static final class WindowEvaluator
            extends ProcessWindowFunction<VelocityAccumulator, RiskScore, String, TimeWindow> {

        private static final Logger log = LoggerFactory.getLogger(WindowEvaluator.class);

        private final int threshold;

        public WindowEvaluator() {
            this.threshold = AppConfig.velocityThreshold();
        }

        @Override
        public void process(
                String userId,
                Context context,
                Iterable<VelocityAccumulator> results,
                Collector<RiskScore> out) {

            VelocityAccumulator acc = results.iterator().next();

            if (acc.count > threshold) {
                log.info("USER_VELOCITY triggered | user_id={} count={} threshold={} window=[{},{})",
                        userId, acc.count, threshold,
                        context.window().getStart(), context.window().getEnd());

                // ArrayList is required here — Kryo copies collections by calling add() on a
                // new instance of the same type. List.of() returns an immutable list, so Kryo's
                // add() call throws UnsupportedOperationException during the copy step.
                List<String> reasons = new ArrayList<>(1);
                reasons.add("USER_VELOCITY");

                out.collect(new RiskScore(
                        acc.lastEventId,
                        75,
                        true,
                        reasons,
                        RULE_VERSION
                ));
            }
        }
    }
}
