package com.nox.application.port.out.health;

import com.nox.domain.health.ComponentHealth;

/** Checks the health of one dependency. */
public interface DependencyHealthProbe {

    ComponentHealth check();
}
