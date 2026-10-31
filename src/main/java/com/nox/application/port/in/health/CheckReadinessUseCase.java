package com.nox.application.port.in.health;

/** Tells whether the service may receive traffic. */
public interface CheckReadinessUseCase {

    boolean execute();
}
