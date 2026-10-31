package com.nox.adapter.in.web.probe;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ServiceHealth;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Knowledge-base health body (contract {@code Health}); {@code critical} is internal and never serialized. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse(
        String status, String serviceId, @Nullable String version, List<Check> checks) {

    static HealthResponse of(ServiceHealth health, String serviceId, @Nullable String version) {
        return new HealthResponse(
                health.status().name(),
                serviceId,
                version,
                health.checks().stream().map(Check::of).toList());
    }

    /** One dependency check. */
    public record Check(String componentId, String componentType, String status, Instant time) {

        static Check of(ComponentHealth health) {
            return new Check(
                    health.componentId(),
                    health.componentType().code(),
                    health.status().name(),
                    health.time());
        }
    }
}
