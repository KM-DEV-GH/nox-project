package com.nox.adapter.out.identityprovider;

import com.nox.application.port.out.health.DependencyHealthProbe;
import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ComponentType;
import com.nox.domain.health.HealthStatus;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Keycloak health derived from the signing key state. Keycloak is critical only until keys are
 * loaded; afterwards cached keys keep the service working, so an outage only degrades it.
 */
@Component
public class KeycloakHealthProbe implements DependencyHealthProbe {

    static final String COMPONENT_ID = "keycloak";

    private final SigningKeyStateTracker tracker;
    private final Clock clock;

    public KeycloakHealthProbe(SigningKeyStateTracker tracker, Clock clock) {
        this.tracker = tracker;
        this.clock = clock;
    }

    @Override
    public ComponentHealth check() {
        SigningKeyState state = tracker.state();
        return new ComponentHealth(
                COMPONENT_ID,
                ComponentType.EXTERNAL_SERVICE,
                statusOf(state),
                state == SigningKeyState.NOT_LOADED,
                clock.instant());
    }

    private static HealthStatus statusOf(SigningKeyState state) {
        return switch (state) {
            case NOT_LOADED -> HealthStatus.DOWN;
            case LOADED -> HealthStatus.UP;
            case LOADED_STALE -> HealthStatus.DEGRADED;
        };
    }
}
