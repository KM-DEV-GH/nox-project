package com.nox.application.service.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.port.out.health.DependencyHealthProbe;
import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ComponentType;
import com.nox.domain.health.HealthStatus;
import com.nox.domain.health.ServiceHealth;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetServiceHealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    @Test
    void aggregatesEveryProbe() {
        ComponentHealth keycloak =
                new ComponentHealth("keycloak", ComponentType.EXTERNAL_SERVICE, HealthStatus.UP, false, NOW);
        ComponentHealth cache = new ComponentHealth("cache", ComponentType.REDIS, HealthStatus.DOWN, false, NOW);
        GetServiceHealthService service = new GetServiceHealthService(List.of(fixed(keycloak), fixed(cache)));

        ServiceHealth health = service.execute();

        assertThat(health.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(health.checks()).containsExactly(keycloak, cache);
    }

    @Test
    void criticalDownProbeMakesServiceDown() {
        ComponentHealth keycloak =
                new ComponentHealth("keycloak", ComponentType.EXTERNAL_SERVICE, HealthStatus.DOWN, true, NOW);

        assertThat(new GetServiceHealthService(List.of(fixed(keycloak)))
                        .execute()
                        .status())
                .isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void noProbesMeansUp() {
        assertThat(new GetServiceHealthService(List.of()).execute().status()).isEqualTo(HealthStatus.UP);
    }

    private static DependencyHealthProbe fixed(ComponentHealth health) {
        return () -> health;
    }
}
