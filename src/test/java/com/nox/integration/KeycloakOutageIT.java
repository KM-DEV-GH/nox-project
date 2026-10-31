package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.NoxApplication;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.InfrastructureContainers;
import com.nox.integration.support.NoxKeycloakContainer;
import com.nox.integration.support.TestCredentials;
import com.nox.integration.support.TestOnlyPingController;
import com.nox.integration.support.TestSourcesExcludeFilter;
import com.nox.integration.support.TestTokens;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak outages (FR-011, FR-017, SC-006, SC-010). Uses its own Keycloak container and starts a
 * fresh application per scenario, because scenario (a) must start while Keycloak is paused.
 *
 * <p>A paused container still accepts TCP connections but never answers, which simulates a slow
 * Keycloak: key fetches must fail by timeout instead of hanging requests.
 */
class KeycloakOutageIT {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static final TestCredentials CREDENTIALS = TestCredentials.generate();
    private static final KeycloakContainer KEYCLOAK = NoxKeycloakContainer.create(CREDENTIALS);
    private static TestTokens tokens;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private ConfigurableApplicationContext application;
    private RestTestClient client;

    @BeforeAll
    static void startKeycloak() {
        KEYCLOAK.start();
        tokens = new TestTokens(KEYCLOAK, CREDENTIALS);
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    @AfterEach
    void stopApplicationAndResumeKeycloak() {
        if (application != null) {
            application.close();
        }
        if (isPaused()) {
            resume();
        }
    }

    @Test
    void startsNotReadyWhileKeycloakIsDownAndBecomesReadyWithoutRestart() {
        pause();
        startApplication();

        client.get()
                .uri("/readyz")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .isEmpty();
        String health = client.get()
                .uri("/healthz")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        ContractAssertions.assertMatchesSchema(jsonMapper.readTree(health), "Health");
        assertThat(jsonMapper.readTree(health).get("status").asString()).isEqualTo("DOWN");

        resume();

        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> client.get()
                                .uri("/readyz")
                                .exchange()
                                .returnResult()
                                .getStatus()
                                .value()
                        == 200);
    }

    @Test
    void staysReadyAndAcceptsKnownKeysWhenKeycloakGoesDown() {
        startApplication();
        awaitReady();
        String aliceToken = tokens.user("alice");

        pause();

        client.get().uri("/readyz").exchange().expectStatus().isOk();
        ping(aliceToken).expectStatus().isOk();
        ping(tokens.unknownKeyId(NoxKeycloakContainer.issuer(KEYCLOAK)))
                .expectStatus()
                .isEqualTo(503);
        String health = client.get()
                .uri("/healthz")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(jsonMapper.readTree(health).get("status").asString()).isEqualTo("DEGRADED");
        assertThat(jsonMapper
                        .readTree(health)
                        .get("checks")
                        .valueStream()
                        .filter(check -> check.get("componentId").asString().equals("keycloak"))
                        .findFirst()
                        .orElseThrow()
                        .get("status")
                        .asString())
                .isEqualTo("DEGRADED");
    }

    @Test
    void unknownKeyWhileKeycloakIsDownFailsClosedWithServiceUnavailableQuickly() {
        startApplication();
        awaitReady();
        String unknownKeyToken = tokens.unknownKeyId(NoxKeycloakContainer.issuer(KEYCLOAK));

        pause();

        long started = System.nanoTime();
        EntityExchangeResult<String> result =
                ping(unknownKeyToken).expectBody(String.class).returnResult();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertProblem(result, 503, "identity_provider_unavailable", TestOnlyPingController.PING);
        assertThat(result.getResponseBody()).doesNotContain("pong");
        assertThat(elapsed)
                .isLessThanOrEqualTo(CONNECT_TIMEOUT.plus(READ_TIMEOUT).plusSeconds(1));
    }

    private RestTestClient.ResponseSpec ping(String token) {
        return client.get()
                .uri(TestOnlyPingController.PING)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private void startApplication() {
        application = new SpringApplicationBuilder(NoxApplication.class, TestOnlyPingController.class)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton(TestSourcesExcludeFilter.class.getName(), new TestSourcesExcludeFilter()))
                .run(Stream.concat(
                                Stream.of(
                                        "--server.port=0",
                                        "--nox.auth.issuer-uri=" + NoxKeycloakContainer.issuer(KEYCLOAK),
                                        "--nox.auth.jwks-uri=" + NoxKeycloakContainer.jwksUri(KEYCLOAK),
                                        "--nox.auth.audience=" + NoxKeycloakContainer.AUDIENCE,
                                        "--nox.auth.jwks-connect-timeout=" + CONNECT_TIMEOUT.toMillis() + "ms",
                                        "--nox.auth.jwks-read-timeout=" + READ_TIMEOUT.toMillis() + "ms"),
                                InfrastructureContainers.arguments().stream())
                        .toArray(String[]::new));
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private void awaitReady() {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> client.get()
                                .uri("/readyz")
                                .exchange()
                                .returnResult()
                                .getStatus()
                                .value()
                        == 200);
    }

    private static void pause() {
        NoxKeycloakContainer.retryingDockerCall(() -> KEYCLOAK.getDockerClient()
                .pauseContainerCmd(KEYCLOAK.getContainerId())
                .exec());
    }

    private static void resume() {
        NoxKeycloakContainer.retryingDockerCall(() -> KEYCLOAK.getDockerClient()
                .unpauseContainerCmd(KEYCLOAK.getContainerId())
                .exec());
    }

    private static boolean isPaused() {
        Boolean paused = NoxKeycloakContainer.retryingDockerCall(() -> KEYCLOAK.getDockerClient()
                        .inspectContainerCmd(KEYCLOAK.getContainerId())
                        .exec())
                .getState()
                .getPaused();
        return Boolean.TRUE.equals(paused);
    }
}
