package com.riskengine.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionEvent(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_ts") Instant eventTs,
    @JsonProperty("user_id") String userId,
    @JsonProperty("merchant_id") String merchantId,
    BigDecimal amount,
    String currency,
    String ip,
    @JsonProperty("device_id") String deviceId,
    String location
) {}
