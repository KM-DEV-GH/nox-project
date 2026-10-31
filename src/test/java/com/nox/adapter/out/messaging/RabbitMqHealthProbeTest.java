package com.nox.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ComponentType;
import com.nox.domain.health.HealthStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

class RabbitMqHealthProbeTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final RabbitMqHealthProbe probe =
            new RabbitMqHealthProbe(connectionFactory, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void openConnectionIsUpAndNotCritical() {
        when(connectionFactory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);

        assertThat(probe.check())
                .isEqualTo(new ComponentHealth("rabbitmq", ComponentType.RABBITMQ, HealthStatus.UP, false, NOW));
    }

    @Test
    void closedConnectionIsDown() {
        when(connectionFactory.createConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(false);

        assertThat(probe.check().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void unreachableBrokerIsDown() {
        when(connectionFactory.createConnection()).thenThrow(new AmqpConnectException(new RuntimeException("refused")));

        assertThat(probe.check().status()).isEqualTo(HealthStatus.DOWN);
    }
}
