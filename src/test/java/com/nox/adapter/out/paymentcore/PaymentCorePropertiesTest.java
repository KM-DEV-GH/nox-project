package com.nox.adapter.out.paymentcore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** The payment-core connection settings owned by {@link PaymentCoreAdapter} (research.md D4, D5). */
class PaymentCorePropertiesTest {

    private static final String BASE_URL = "nox.payment.payment-core.base-url=http://payment-core.test";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PaymentCorePropertiesConfig.class);

    @Test
    void bindsAllPropertiesWithDefaults() {
        runner.withPropertyValues(BASE_URL).run(context -> {
            PaymentCoreProperties properties = context.getBean(PaymentCoreProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("http://payment-core.test");
            assertThat(properties.registrationId()).isEqualTo("payment-core");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.backoff()).isEqualTo(Duration.ofMillis(200));
            assertThat(properties.circuitBreaker().failureRateThreshold()).isEqualTo(50);
            assertThat(properties.circuitBreaker().slidingWindowSize()).isEqualTo(20);
            assertThat(properties.circuitBreaker().minimumNumberOfCalls()).isEqualTo(10);
            assertThat(properties.circuitBreaker().waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.socks5Enabled()).isFalse();
            assertThat(properties.socks5Host()).isEmpty();
            assertThat(properties.socks5Port()).isEqualTo(1080);
        });
    }

    /** The same SOCKS5 settings as Owl's, for payment-core and its token requests. */
    @Test
    void bindsTheSocks5Proxy() {
        runner.withPropertyValues(
                        BASE_URL,
                        "nox.payment.payment-core.socks5-enabled=true",
                        "nox.payment.payment-core.socks5-host=proxy.test",
                        "nox.payment.payment-core.socks5-port=1081")
                .run(context -> {
                    PaymentCoreProperties properties = context.getBean(PaymentCoreProperties.class);
                    assertThat(properties.socks5Enabled()).isTrue();
                    assertThat(properties.socks5Host()).isEqualTo("proxy.test");
                    assertThat(properties.socks5Port()).isEqualTo(1081);
                });
    }

    @Test
    void rejectsABlankBaseUrl() {
        runner.withPropertyValues("nox.payment.payment-core.base-url= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout", "read-timeout"})
    void rejectsANonPositiveTimeout(String timeout) {
        runner.withPropertyValues(BASE_URL, "nox.payment.payment-core." + timeout + "=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsZeroMaxAttempts() {
        runner.withPropertyValues(BASE_URL, "nox.payment.payment-core.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAZeroSocks5Port() {
        runner.withPropertyValues(BASE_URL, "nox.payment.payment-core.socks5-port=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAFailureRateAboveOneHundred() {
        runner.withPropertyValues(BASE_URL, "nox.payment.payment-core.circuit-breaker.failure-rate-threshold=101")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentCoreProperties.class)
    static class PaymentCorePropertiesConfig {}
}
