package com.riskengine.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;

import java.io.IOException;

public class TransactionEventDeserializer implements DeserializationSchema<TransactionEvent> {

    private transient ObjectMapper mapper;

    private ObjectMapper mapper() {
        if (mapper == null) {
            mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        }
        return mapper;
    }

    @Override
    public TransactionEvent deserialize(byte[] message) throws IOException {
        return mapper().readValue(message, TransactionEvent.class);
    }

    @Override
    public boolean isEndOfStream(TransactionEvent nextElement) {
        return false;
    }

    @Override
    public TypeInformation<TransactionEvent> getProducedType() {
        return TypeInformation.of(TransactionEvent.class);
    }
}
