package com.riskengine.engine.preprocess;

import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes events that arrive behind the current watermark to a side output.
 */
public final class LateEventRouterFunction extends ProcessFunction<TransactionEvent, TransactionEvent> {

    private static final Logger log = LoggerFactory.getLogger(LateEventRouterFunction.class);

    private final OutputTag<TransactionEvent> lateEventsTag;

    public LateEventRouterFunction(OutputTag<TransactionEvent> lateEventsTag) {
        this.lateEventsTag = lateEventsTag;
    }

    @Override
    public void processElement(
            TransactionEvent event,
            Context ctx,
            Collector<TransactionEvent> out) {

        long currentWatermark = ctx.timerService().currentWatermark();
        long eventTs = event.eventTs().toEpochMilli();

        if (currentWatermark != Long.MIN_VALUE && eventTs <= currentWatermark) {
            log.warn("Late event routed | event_id={} event_ts={} watermark={}",
                    event.eventId(), event.eventTs(), currentWatermark);
            ctx.output(lateEventsTag, event);
            return;
        }

        out.collect(event);
    }
}
