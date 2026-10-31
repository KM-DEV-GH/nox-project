package com.nox.domain.health;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of checking one dependency.
 *
 * @param componentId stable name of the dependency (e.g. {@code keycloak})
 * @param componentType kind of dependency
 * @param status the dependency's health
 * @param critical whether the service cannot work at all while this dependency is down
 * @param time when the check was made
 */
public record ComponentHealth(
        String componentId, ComponentType componentType, HealthStatus status, boolean critical, Instant time) {

    public ComponentHealth {
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(componentType, "componentType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(time, "time");
    }
}
