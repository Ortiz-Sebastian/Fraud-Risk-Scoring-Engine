package com.riskengine.engine.fraud;

import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Device profiling rule: flag a brand-new device_id whose very first transaction
 * exceeds an amount threshold (default $500). Catches the ATTACK mode's
 * new-device attack pattern (fresh UUID device, $500–$3000 purchase).
 *
 * <p>Implementation uses a {@link KeyedProcessFunction} keyed by {@code device_id}
 * with {@link ValueState} tracking whether the device has been seen before. State is
 * configured with {@link StateTtlConfig} using {@code OnReadAndWrite} so that the
 * TTL is refreshed on every event — acting as a session-inactivity gap. Once a device
 * goes dormant for longer than the retention period, its state expires and the next
 * transaction is treated as a first-time event.
 *
 * <p>Threshold is read from {@code AppConfig.newDeviceAmountThreshold()} (env:
 * {@code NEW_DEVICE_AMOUNT_THRESHOLD}, default 500). State retention is read from
 * {@code AppConfig.deviceStateRetentionMinutes()} (env: {@code DEVICE_STATE_RETENTION_MINUTES},
 * default 1440 = 24 hours).
 */
public final class DeviceProfileDetector {

    static final String RULE_VERSION = "v1.0";

    private DeviceProfileDetector() {}

    /**
     * Stateful process function that emits a {@link RiskScore} when a device_id's
     * first-ever transaction exceeds the configured amount threshold.
     *
     * <p>State lifecycle:
     * <ol>
     *   <li>First event for a device → check amount, flag if above threshold, mark device as seen</li>
     *   <li>Subsequent events → no-op (device is known); TTL is refreshed by the read</li>
     *   <li>After inactivity exceeding the TTL → state expires, device is treated as new again</li>
     * </ol>
     */
    public static final class NewDeviceFunction
            extends KeyedProcessFunction<String, TransactionEvent, RiskScore> {

        private static final Logger log = LoggerFactory.getLogger(NewDeviceFunction.class);

        private final BigDecimal amountThreshold;
        private final long stateRetentionMinutes;

        private transient ValueState<Boolean> deviceSeenState;

        public NewDeviceFunction() {
            this(BigDecimal.valueOf(AppConfig.newDeviceAmountThreshold()),
                 AppConfig.deviceStateRetentionMinutes());
        }

        public NewDeviceFunction(BigDecimal amountThreshold, long stateRetentionMinutes) {
            this.amountThreshold = amountThreshold;
            this.stateRetentionMinutes = stateRetentionMinutes;
        }

        @Override
        public void open(Configuration parameters) {
            StateTtlConfig ttlConfig = StateTtlConfig
                    .newBuilder(Time.minutes(stateRetentionMinutes))
                    .setUpdateType(StateTtlConfig.UpdateType.OnReadAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                    .build();

            ValueStateDescriptor<Boolean> descriptor =
                    new ValueStateDescriptor<>("device-seen", Boolean.class);
            descriptor.enableTimeToLive(ttlConfig);

            deviceSeenState = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void processElement(
                TransactionEvent event,
                Context ctx,
                Collector<RiskScore> out) throws Exception {

            Boolean seen = deviceSeenState.value();

            if (seen != null) {
                return;
            }

            deviceSeenState.update(true);

            if (event.amount().compareTo(amountThreshold) >= 0) {
                log.info("NEW_DEVICE_HIGH_VALUE triggered | device_id={} amount={} threshold={}",
                        event.deviceId(), event.amount(), amountThreshold);

                List<String> reasons = new ArrayList<>(1);
                reasons.add("NEW_DEVICE_HIGH_VALUE");

                out.collect(new RiskScore(
                        event.eventId(),
                        85,
                        true,
                        reasons,
                        RULE_VERSION
                ));
            }
        }
    }
}
