package com.nox.adapter.out.identityprovider;

import com.nox.application.port.out.health.ReadinessCondition;
import org.springframework.stereotype.Component;

/** Ready once the realm's signing keys have been loaded at least once (FR-017). */
@Component
public class KeycloakReadinessCondition implements ReadinessCondition {

    private final SigningKeyStateTracker tracker;

    public KeycloakReadinessCondition(SigningKeyStateTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public boolean isReady() {
        return tracker.isReady();
    }
}
