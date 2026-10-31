package com.nox.integration.support;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The real PostgreSQL and RabbitMQ, and the WireMock stand-in for Owl and payment-core, shared by
 * every integration test (constitution Principle IV, research.md decision 2; 005 research.md D7).
 * Started once per JVM. Owl and payment-core paths do not overlap, so one WireMock serves both. Only
 * placeholder credentials are used, never real keys.
 */
public final class InfrastructureContainers {

    public static final String OWL_API_KEY = "test-api-key";
    public static final String OWL_CLIENT_ID = "nox-test";
    public static final String OWL_CLIENT_SECRET = "test-client-secret";
    public static final String OWL_TOKEN_PATH = "/oauth/token";
    public static final String PAYMENT_CORE_CLIENT_ID = "nox-payments-test";
    public static final String PAYMENT_CORE_CLIENT_SECRET = "test-payment-core-secret";
    public static final String PAYMENT_CORE_TOKEN_PATH = "/payment-core/oauth/token";
    public static final String PAYIN_METHOD = "TEST_PAYIN";
    public static final String PAYOUT_METHOD = "TEST_PAYOUT";

    private static final int STARTUP_ATTEMPTS = 3;
    private static final int WIREMOCK_PORT = 8080;

    public static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.6-alpine").withStartupAttempts(STARTUP_ATTEMPTS);
    public static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.4.0-management-alpine").withStartupAttempts(STARTUP_ATTEMPTS);
    public static final GenericContainer<?> OWL = new GenericContainer<>("wiremock/wiremock:3.13.2")
            .withExposedPorts(WIREMOCK_PORT)
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200))
            .withStartupAttempts(STARTUP_ATTEMPTS);

    static {
        POSTGRES.start();
        RABBITMQ.start();
        OWL.start();
    }

    private InfrastructureContainers() {}

    public static String owlBaseUrl() {
        return "http://" + OWL.getHost() + ":" + OWL.getMappedPort(WIREMOCK_PORT);
    }

    public static int owlPort() {
        return OWL.getMappedPort(WIREMOCK_PORT);
    }

    /** Payment-core is served by the same WireMock as Owl. */
    public static String paymentCoreBaseUrl() {
        return owlBaseUrl();
    }

    /**
     * Application properties pointing at the containers, with short Owl retries and approval window,
     * and short payment-core timeouts. The payment-core circuit breaker needs more calls than any
     * test run makes, so timeout tests cannot open it for the tests after them.
     */
    public static Map<String, Supplier<Object>> properties() {
        return Map.ofEntries(
                Map.entry("spring.datasource.url", POSTGRES::getJdbcUrl),
                Map.entry("spring.datasource.username", POSTGRES::getUsername),
                Map.entry("spring.datasource.password", POSTGRES::getPassword),
                Map.entry("spring.rabbitmq.host", RABBITMQ::getHost),
                Map.entry("spring.rabbitmq.port", RABBITMQ::getAmqpPort),
                Map.entry("spring.rabbitmq.username", RABBITMQ::getAdminUsername),
                Map.entry("spring.rabbitmq.password", RABBITMQ::getAdminPassword),
                Map.entry("spring.security.oauth2.client.registration.owl.client-id", () -> OWL_CLIENT_ID),
                Map.entry("spring.security.oauth2.client.registration.owl.client-secret", () -> OWL_CLIENT_SECRET),
                Map.entry("spring.security.oauth2.client.provider.owl.token-uri", () -> owlBaseUrl() + OWL_TOKEN_PATH),
                Map.entry("nox.partner-link.owl.base-url", InfrastructureContainers::owlBaseUrl),
                Map.entry("nox.partner-link.owl.api-key", () -> OWL_API_KEY),
                Map.entry("nox.partner-link.owl.read-timeout", () -> "1s"),
                Map.entry("nox.partner-link.owl.max-attempts", () -> "3"),
                Map.entry("nox.partner-link.owl.backoff", () -> "100ms"),
                Map.entry("nox.partner-link.approval-window", () -> "2m"),
                Map.entry(
                        "spring.security.oauth2.client.registration.payment-core.client-id",
                        () -> PAYMENT_CORE_CLIENT_ID),
                Map.entry(
                        "spring.security.oauth2.client.registration.payment-core.client-secret",
                        () -> PAYMENT_CORE_CLIENT_SECRET),
                Map.entry(
                        "spring.security.oauth2.client.provider.payment-core.token-uri",
                        () -> paymentCoreBaseUrl() + PAYMENT_CORE_TOKEN_PATH),
                Map.entry("nox.payment.payin-method", () -> PAYIN_METHOD),
                Map.entry("nox.payment.payout-method", () -> PAYOUT_METHOD),
                Map.entry("nox.payment.payment-core.base-url", InfrastructureContainers::paymentCoreBaseUrl),
                Map.entry("nox.payment.payment-core.read-timeout", () -> "1s"),
                Map.entry("nox.payment.payment-core.max-attempts", () -> "2"),
                Map.entry("nox.payment.payment-core.backoff", () -> "50ms"),
                Map.entry("nox.payment.payment-core.circuit-breaker.sliding-window-size", () -> "1000"),
                Map.entry("nox.payment.payment-core.circuit-breaker.minimum-number-of-calls", () -> "1000"));
    }

    /** {@link #properties()} as command-line arguments, for applications started outside the test context. */
    public static List<String> arguments() {
        return properties().entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue().get())
                .toList();
    }
}
