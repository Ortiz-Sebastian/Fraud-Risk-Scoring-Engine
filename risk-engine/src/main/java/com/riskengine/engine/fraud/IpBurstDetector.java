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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IP burst detection rule: flag an IP address when more than THRESHOLD distinct
 * users transact from it within a 2-minute sliding window (slide every 30 seconds).
 *
 * <p>Uses Flink's two-stage aggregate pattern:
 * <ol>
 *   <li>{@link DistinctUserAggregator} — maintains a {@link HashSet} of user_ids seen in
 *       the window. Unlike velocity detection (which only needs a counter), distinct-value
 *       counting requires holding a set in memory. The set is bounded by the realistic
 *       number of distinct users per IP per 2-minute window.</li>
 *   <li>{@link WindowEvaluator} — receives the finished accumulator once per window close,
 *       compares the distinct user count against the threshold, and emits a {@link RiskScore}
 *       when exceeded.</li>
 * </ol>
 *
 * <p>Threshold is read from {@code AppConfig.ipBurstThreshold()} (env: {@code IP_BURST_THRESHOLD},
 * default 5) so it can be tuned without a code change.
 */
public final class IpBurstDetector {

    static final String RULE_VERSION = "v1.0";

    private IpBurstDetector() {}

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    /**
     * Tracks distinct user IDs and the most-recently-seen event_id within
     * a window. The {@link HashSet} is the minimum state required for
     * distinct-value counting — there is no O(1) alternative.
     */
    public static final class IpBurstAccumulator {
        public Set<String> distinctUserIds = new HashSet<>();
        public String lastEventId = "";
    }

    // -------------------------------------------------------------------------
    // Stage 1: per-record accumulation
    // -------------------------------------------------------------------------

    /**
     * Flink {@link AggregateFunction} that collects distinct user_ids and tracks
     * the last event_id for each record in the window.
     *
     * <p>{@code merge()} unions the two sets, which is correct for session-window
     * merges. Sliding windows never call merge, but it is implemented for completeness.
     */
    public static final class DistinctUserAggregator
            implements AggregateFunction<TransactionEvent, IpBurstAccumulator, IpBurstAccumulator> {

        @Override
        public IpBurstAccumulator createAccumulator() {
            return new IpBurstAccumulator();
        }

        @Override
        public IpBurstAccumulator add(TransactionEvent event, IpBurstAccumulator acc) {
            acc.distinctUserIds.add(event.userId());
            acc.lastEventId = event.eventId();
            return acc;
        }

        @Override
        public IpBurstAccumulator getResult(IpBurstAccumulator acc) {
            return acc;
        }

        @Override
        public IpBurstAccumulator merge(IpBurstAccumulator a, IpBurstAccumulator b) {
            a.distinctUserIds.addAll(b.distinctUserIds);
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
     * {@link IpBurstAccumulator} for a closed window, compares the distinct-user
     * count against the configured threshold, and emits a {@link RiskScore} when exceeded.
     *
     * <p>The threshold is captured at construction time so it is read once per
     * Flink subtask lifecycle rather than on every window close.
     */
    public static final class WindowEvaluator
            extends ProcessWindowFunction<IpBurstAccumulator, RiskScore, String, TimeWindow> {

        private static final Logger log = LoggerFactory.getLogger(WindowEvaluator.class);

        private final int threshold;

        public WindowEvaluator() {
            this.threshold = AppConfig.ipBurstThreshold();
        }

        public WindowEvaluator(int threshold) {
            this.threshold = threshold;
        }

        @Override
        public void process(
                String ip,
                Context context,
                Iterable<IpBurstAccumulator> results,
                Collector<RiskScore> out) {

            IpBurstAccumulator acc = results.iterator().next();
            int distinctCount = acc.distinctUserIds.size();

            if (distinctCount > threshold) {
                log.info("IP_BURST triggered | ip={} distinctUsers={} threshold={} window=[{},{})",
                        ip, distinctCount, threshold,
                        context.window().getStart(), context.window().getEnd());

                List<String> reasons = new ArrayList<>(1);
                reasons.add("IP_BURST");

                out.collect(new RiskScore(
                        acc.lastEventId,
                        80,
                        true,
                        reasons,
                        RULE_VERSION
                ));
            }
        }
    }
}
