package com.riskengine.engine.preprocess;

import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PreprocessingPipelineTest {

    private static final OutputTag<TransactionEvent> LATE_TAG = new OutputTag<>("late-events-test") {};
    private static final List<String> mainIds = new CopyOnWriteArrayList<>();
    private static final List<String> lateIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearCapture() {
        mainIds.clear();
        lateIds.clear();
    }

    @Test
    void routes_late_events_and_deduplicates_on_time_events() throws Exception {
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        List<TransactionEvent> events = List.of(
                event("evt-1", base),
                event("evt-2", base.plusSeconds(20)),
                event("evt-late", base.plusSeconds(5)),
                event("evt-2", base.plusSeconds(21))
        );

        runPipeline(events);

        assertThat(mainIds).contains("evt-1", "evt-2");
        assertThat(mainIds.stream().filter(id -> Objects.equals(id, "evt-2")).count())
                .isEqualTo(1L);
        assertThat(lateIds).contains("evt-late");
    }

    private static void runPipeline(List<TransactionEvent> events) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        final long outOfOrdernessMillis = Duration.ofSeconds(2).toMillis();
        WatermarkStrategy<TransactionEvent> watermarks =
                WatermarkStrategy.<TransactionEvent>forGenerator(context ->
                                new OnEventBoundedWatermarkGenerator(outOfOrdernessMillis))
                        .withTimestampAssigner((event, ts) -> event.eventTs().toEpochMilli());

        SingleOutputStreamOperator<TransactionEvent> onTime = env.fromCollection(events)
                .assignTimestampsAndWatermarks(watermarks)
                .process(new LateEventRouterFunction(LATE_TAG));

        DataStream<TransactionEvent> deduped = onTime
                .keyBy(TransactionEvent::eventId)
                .process(new EventDeduplicationFunction(60));

        deduped.addSink(new MainEventCapturingSink());
        onTime.getSideOutput(LATE_TAG).addSink(new LateEventCapturingSink());

        env.execute("preprocessing-pipeline-test");
    }

    /**
     * Deterministic on-event watermarking for tests.
     * Emits watermark=maxSeenTs-outOfOrderness on every event so late routing is stable
     * even when periodic watermark emission does not run during short bounded inputs.
     */
    static class OnEventBoundedWatermarkGenerator implements WatermarkGenerator<TransactionEvent> {

        private final long outOfOrdernessMillis;
        private long maxTimestampSeen = Long.MIN_VALUE;

        OnEventBoundedWatermarkGenerator(long outOfOrdernessMillis) {
            this.outOfOrdernessMillis = outOfOrdernessMillis;
        }

        @Override
        public void onEvent(TransactionEvent event, long eventTimestamp, WatermarkOutput output) {
            maxTimestampSeen = Math.max(maxTimestampSeen, eventTimestamp);
            output.emitWatermark(new org.apache.flink.api.common.eventtime.Watermark(
                    maxTimestampSeen - outOfOrdernessMillis));
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
            // No-op: this test intentionally emits watermarks on each event.
        }
    }

    private static TransactionEvent event(String eventId, Instant ts) {
        return new TransactionEvent(
                eventId,
                ts,
                "user-1",
                "merchant-1",
                BigDecimal.valueOf(100),
                "USD",
                "10.0.0.1",
                "device-1",
                "US"
        );
    }

    static class MainEventCapturingSink extends RichSinkFunction<TransactionEvent> {

        @Override
        public void open(Configuration parameters) {
            // No resources required.
        }

        @Override
        public void invoke(TransactionEvent value, Context context) {
            mainIds.add(value.eventId());
        }
    }

    static class LateEventCapturingSink extends RichSinkFunction<TransactionEvent> {

        @Override
        public void open(Configuration parameters) {
            // No resources required.
        }

        @Override
        public void invoke(TransactionEvent value, Context context) {
            lateIds.add(value.eventId());
        }
    }
}
