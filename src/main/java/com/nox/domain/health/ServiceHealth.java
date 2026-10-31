package com.nox.domain.health;

import java.util.List;

/**
 * Overall health of the service and the checks it was derived from.
 *
 * @param status aggregated status
 * @param checks the individual dependency checks
 */
public record ServiceHealth(HealthStatus status, List<ComponentHealth> checks) {

    public ServiceHealth {
        checks = List.copyOf(checks);
    }

    /**
     * {@code DOWN} if any critical check is {@code DOWN}; otherwise {@code DEGRADED} if any check is
     * not {@code UP}; otherwise {@code UP}. No checks means {@code UP}.
     */
    public static ServiceHealth aggregate(List<ComponentHealth> checks) {
        return new ServiceHealth(statusOf(checks), checks);
    }

    private static HealthStatus statusOf(List<ComponentHealth> checks) {
        if (checks.stream().anyMatch(check -> check.critical() && check.status() == HealthStatus.DOWN)) {
            return HealthStatus.DOWN;
        }
        if (checks.stream().anyMatch(check -> check.status() != HealthStatus.UP)) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.UP;
    }
}
