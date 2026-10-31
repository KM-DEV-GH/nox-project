package com.nox.domain.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceHealthTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    @Test
    void noChecksMeansUp() {
        assertThat(ServiceHealth.aggregate(List.of()).status()).isEqualTo(HealthStatus.UP);
    }

    static Stream<Arguments> combinations() {
        return Stream.of(
                Arguments.of(List.of(check(HealthStatus.UP, true)), HealthStatus.UP),
                Arguments.of(List.of(check(HealthStatus.UP, false)), HealthStatus.UP),
                Arguments.of(List.of(check(HealthStatus.DOWN, true)), HealthStatus.DOWN),
                Arguments.of(List.of(check(HealthStatus.DOWN, false)), HealthStatus.DEGRADED),
                Arguments.of(List.of(check(HealthStatus.DEGRADED, true)), HealthStatus.DEGRADED),
                Arguments.of(List.of(check(HealthStatus.DEGRADED, false)), HealthStatus.DEGRADED),
                Arguments.of(List.of(check(HealthStatus.UP, true), check(HealthStatus.UP, false)), HealthStatus.UP),
                Arguments.of(
                        List.of(check(HealthStatus.UP, true), check(HealthStatus.DOWN, false)), HealthStatus.DEGRADED),
                Arguments.of(
                        List.of(check(HealthStatus.DEGRADED, false), check(HealthStatus.DOWN, true)),
                        HealthStatus.DOWN),
                Arguments.of(
                        List.of(check(HealthStatus.DOWN, false), check(HealthStatus.DOWN, true)), HealthStatus.DOWN),
                Arguments.of(
                        List.of(check(HealthStatus.DEGRADED, true), check(HealthStatus.DOWN, false)),
                        HealthStatus.DEGRADED));
    }

    @ParameterizedTest
    @MethodSource("combinations")
    void aggregatesChecks(List<ComponentHealth> checks, HealthStatus expected) {
        ServiceHealth health = ServiceHealth.aggregate(checks);

        assertThat(health.status()).isEqualTo(expected);
        assertThat(health.checks()).containsExactlyElementsOf(checks);
    }

    @Test
    void checksAreImmutable() {
        List<ComponentHealth> checks = new ArrayList<>(List.of(check(HealthStatus.UP, true)));
        ServiceHealth health = ServiceHealth.aggregate(checks);
        checks.clear();

        assertThat(health.checks()).hasSize(1);
    }

    @Test
    void componentTypeHasLowercaseHyphenatedCode() {
        assertThat(ComponentType.EXTERNAL_SERVICE.code()).isEqualTo("external-service");
        assertThat(ComponentType.POSTGRESQL.code()).isEqualTo("postgresql");
    }

    private static ComponentHealth check(HealthStatus status, boolean critical) {
        return new ComponentHealth("component", ComponentType.EXTERNAL_SERVICE, status, critical, NOW);
    }
}
