package com.riskengine.engine.preprocess;

import com.riskengine.common.config.AppConfig;
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

/**
 * Deduplicates events by event_id with bounded state via TTL.
 *
 * <p>Expected usage: stream keyed by {@code TransactionEvent::eventId}.
 * For each key:
 * <ul>
 *   <li>First-seen event passes downstream and marks the key as seen.</li>
 *   <li>Subsequent duplicates within TTL are dropped.</li>
 * </ul>
 */
public final class EventDeduplicationFunction
        extends KeyedProcessFunction<String, TransactionEvent, TransactionEvent> {

    private static final Logger log = LoggerFactory.getLogger(EventDeduplicationFunction.class);

    private final long stateRetentionMinutes;
    private transient ValueState<Boolean> seenState;

    public EventDeduplicationFunction() {
        this(AppConfig.dedupStateRetentionMinutes());
    }

    public EventDeduplicationFunction(long stateRetentionMinutes) {
        this.stateRetentionMinutes = stateRetentionMinutes;
    }

    @Override
    public void open(Configuration parameters) {
        StateTtlConfig ttlConfig = StateTtlConfig
                .newBuilder(Time.minutes(stateRetentionMinutes))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .build();

        ValueStateDescriptor<Boolean> descriptor =
                new ValueStateDescriptor<>("seen-event-id", Boolean.class);
        descriptor.enableTimeToLive(ttlConfig);
        seenState = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(
            TransactionEvent event,
            Context ctx,
            Collector<TransactionEvent> out) throws Exception {

        Boolean seen = seenState.value();
        if (seen != null) {
            log.debug("Duplicate dropped | event_id={}", event.eventId());
            return;
        }

        seenState.update(true);
        out.collect(event);
    }
}
