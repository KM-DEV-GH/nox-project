package com.nox.application.port.in.health;

import com.nox.domain.health.ServiceHealth;

/** Reports the health of the service and its dependencies. */
public interface GetServiceHealthUseCase {

    ServiceHealth execute();
}
