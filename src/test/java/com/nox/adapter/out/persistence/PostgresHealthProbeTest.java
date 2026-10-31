package com.nox.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nox.domain.health.ComponentHealth;
import com.nox.domain.health.ComponentType;
import com.nox.domain.health.HealthStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresHealthProbeTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PostgresHealthProbe probe = new PostgresHealthProbe(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validConnectionIsUpAndCritical() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        assertThat(probe.check())
                .isEqualTo(new ComponentHealth("postgresql", ComponentType.POSTGRESQL, HealthStatus.UP, true, NOW));
        verify(connection).close();
    }

    @Test
    void invalidConnectionIsDown() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);

        assertThat(probe.check().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void unobtainableConnectionIsDown() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThat(probe.check().status()).isEqualTo(HealthStatus.DOWN);
    }
}
