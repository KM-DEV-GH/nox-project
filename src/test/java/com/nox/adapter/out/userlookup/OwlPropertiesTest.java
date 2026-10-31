package com.nox.adapter.out.userlookup;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.adapter.out.http.CircuitBreakerSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class OwlPropertiesTest {

    private static final String BASE_URL = "nox.partner-link.owl.base-url=http://owl.test";
    private static final String API_KEY = "nox.partner-link.owl.api-key=test-api-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(OwlPropertiesConfig.class);

    @Test
    void bindsAllPropertiesWithDefaults() {
        runner.withPropertyValues(BASE_URL, API_KEY).run(context -> {
            OwlProperties properties = context.getBean(OwlProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("http://owl.test");
            assertThat(properties.apiKey()).isEqualTo("test-api-key");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.maxAttempts()).isEqualTo(4);
            assertThat(properties.backoff()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.circuitBreaker())
                    .isEqualTo(new CircuitBreakerSettings(50, 20, 10, Duration.ofSeconds(30)));
            assertThat(properties.socks5Enabled()).isFalse();
            assertThat(properties.socks5Host()).isEmpty();
            assertThat(properties.socks5Port()).isEqualTo(1080);
        });
    }

    @Test
    void rejectsBlankApiKey() {
        runner.withPropertyValues(BASE_URL, "nox.partner-link.owl.api-key= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsZeroMaxAttempts() {
        runner.withPropertyValues(BASE_URL, API_KEY, "nox.partner-link.owl.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void neverPrintsTheApiKey() {
        OwlProperties properties = new OwlProperties(
                "http://owl.test",
                "test-api-key",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                4,
                Duration.ofMillis(500),
                new CircuitBreakerSettings(50, 20, 10, Duration.ofSeconds(30)),
                false,
                "",
                1080);

        assertThat(properties.toString()).doesNotContain("test-api-key").contains("redacted");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OwlProperties.class)
    static class OwlPropertiesConfig {}
}
