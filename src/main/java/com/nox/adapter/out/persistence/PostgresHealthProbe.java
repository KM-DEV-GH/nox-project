package com.nox.adapter.out.persistence;

import com.nox.application.port.out.health.DependencyHealthProbe;
import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ComponentType;
import com.nox.domain.health.HealthStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL health from a pooled connection's validity check. Critical: every partner link
 * operation and the event publication registry need the database.
 */
@Component
public class PostgresHealthProbe implements DependencyHealthProbe {

    static final String COMPONENT_ID = "postgresql";

    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final Clock clock;

    public PostgresHealthProbe(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public ComponentHealth check() {
        return new ComponentHealth(COMPONENT_ID, ComponentType.POSTGRESQL, status(), true, clock.instant());
    }

    private HealthStatus status() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? HealthStatus.UP : HealthStatus.DOWN;
        } catch (SQLException e) {
            return HealthStatus.DOWN;
        }
    }
}
