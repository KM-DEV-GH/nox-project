package com.nox.application.port.out.health;

/** One condition that must hold before the service may receive traffic. */
public interface ReadinessCondition {

    boolean isReady();
}
