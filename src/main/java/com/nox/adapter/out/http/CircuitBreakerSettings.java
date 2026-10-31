package com.nox.adapter.out.http;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param failureRateThreshold failure percentage that opens the circuit
 * @param slidingWindowSize calls considered for the failure rate
 * @param minimumNumberOfCalls calls needed before the rate is evaluated
 * @param waitDurationInOpenState time the circuit stays open before probing again
 */
public record CircuitBreakerSettings(
        @Min(1) @Max(100) @DefaultValue("50") int failureRateThreshold,
        @Min(1) @DefaultValue("20") int slidingWindowSize,
        @Min(1) @DefaultValue("10") int minimumNumberOfCalls,

        @NotNull @DurationMin(millis = 1) @DefaultValue("30s")
        Duration waitDurationInOpenState) {}
