package com.nox.adapter.out.messaging;

import com.nox.application.port.out.health.DependencyHealthProbe;
import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ComponentType;
import com.nox.domain.health.HealthStatus;
import java.time.Clock;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ health from the shared broker connection. Not critical: outgoing messages wait in the
 * event publication registry and are resubmitted once the broker is back, so an outage only
 * delays them.
 */
@Component
public class RabbitMqHealthProbe implements DependencyHealthProbe {

    static final String COMPONENT_ID = "rabbitmq";

    private final ConnectionFactory connectionFactory;
    private final Clock clock;

    public RabbitMqHealthProbe(ConnectionFactory connectionFactory, Clock clock) {
        this.connectionFactory = connectionFactory;
        this.clock = clock;
    }

    @Override
    public ComponentHealth check() {
        return new ComponentHealth(COMPONENT_ID, ComponentType.RABBITMQ, status(), false, clock.instant());
    }

    // Closing the caching factory's shared connection is a no-op, so this never drops it.
    private HealthStatus status() {
        try (Connection connection = connectionFactory.createConnection()) {
            return connection.isOpen() ? HealthStatus.UP : HealthStatus.DOWN;
        } catch (AmqpException e) {
            return HealthStatus.DOWN;
        }
    }
}
