package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.KeycloakContainerSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SC-004: authentication adds at most 50 ms at p95 compared with an unsecured request. Excluded
 * from the default build; run with {@code ./mvnw verify -Dgroups=perf}.
 */
@Tag("perf")
@Import(SecurityLatencyPerfIT.UnsecuredEcho.class)
class SecurityLatencyPerfIT extends KeycloakContainerSupport {

    private static final String UNSECURED_ECHO = "/test-only/unsecured-echo";
    private static final int WARM_UP = 500;
    private static final int SAMPLES = 500;
    private static final Duration MAX_P95_OVERHEAD = Duration.ofMillis(50);

    @Test
    void authenticationAddsAtMostFiftyMillisecondsAtP95() {
        String token = TOKENS.user("alice");
        Runnable secured = () -> client.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();
        Runnable unsecured =
                () -> client.get().uri(UNSECURED_ECHO).exchange().expectStatus().isOk();

        repeat(secured, WARM_UP);
        repeat(unsecured, WARM_UP);
        Duration securedP95 = p95(() -> time(secured));
        Duration unsecuredP95 = p95(() -> time(unsecured));

        assertThat(securedP95.minus(unsecuredP95)).isLessThanOrEqualTo(MAX_P95_OVERHEAD);
    }

    private static void repeat(Runnable request, int times) {
        for (int i = 0; i < times; i++) {
            request.run();
        }
    }

    private static Duration time(Runnable request) {
        long started = System.nanoTime();
        request.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static Duration p95(Supplier<Duration> sample) {
        List<Duration> samples = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            samples.add(sample.get());
        }
        Collections.sort(samples);
        return samples.get((int) Math.ceil(SAMPLES * 0.95) - 1);
    }

    /** A test-only endpoint outside the API security chain, used as the latency baseline. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(UnsecuredEcho.Endpoint.class)
    static class UnsecuredEcho {

        @Bean
        @Order(0)
        SecurityFilterChain unsecuredEchoSecurity(HttpSecurity http) {
            return http.securityMatcher(UNSECURED_ECHO)
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }

        @RestController
        static class Endpoint {

            @GetMapping(UNSECURED_ECHO)
            String echo() {
                return "echo";
            }
        }
    }
}
