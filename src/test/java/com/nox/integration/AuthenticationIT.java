package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.TestOnlyPingController;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** US1 scenarios 1–6: only valid Keycloak tokens for this service reach a protected endpoint. */
class AuthenticationIT extends KeycloakContainerSupport {

    private static final String PING = TestOnlyPingController.PING;
    private static final Set<String> DETAILS = new HashSet<>();

    @BeforeAll
    static void resetDetails() {
        DETAILS.clear();
    }

    @AfterAll
    static void everyRejectionUsesTheSameGenericDetail() {
        assertThat(DETAILS).hasSize(1);
    }

    static Stream<Arguments> rejectedAuthorizationHeaders() {
        return Stream.of(
                Arguments.of("Basic scheme", (Supplier<String>) () -> "Basic YWxpY2U6c2VjcmV0"),
                Arguments.of("empty Bearer", (Supplier<String>) () -> "Bearer "),
                Arguments.of(
                        "two tokens", (Supplier<String>) () -> "Bearer " + TOKENS.service() + " " + TOKENS.service()),
                Arguments.of("tampered", (Supplier<String>) () -> "Bearer " + TOKENS.tampered()),
                Arguments.of("alg=none", (Supplier<String>) () -> "Bearer " + TOKENS.algNone()),
                Arguments.of("foreign issuer", (Supplier<String>) () -> "Bearer " + TOKENS.foreignIssuer()),
                Arguments.of("other-app audience", (Supplier<String>) () -> "Bearer " + TOKENS.otherApp()));
    }

    @Test
    void rejectsRequestWithoutAuthorizationHeader() {
        assertUnauthenticated(
                client.get().uri(PING).exchange().expectBody(String.class).returnResult());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedAuthorizationHeaders")
    void rejectsInvalidCredentials(String description, Supplier<String> authorization) {
        EntityExchangeResult<String> result = client.get()
                .uri(PING)
                .header(HttpHeaders.AUTHORIZATION, authorization.get())
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertUnauthenticated(result);
    }

    @Test
    void rejectsTwoAuthorizationHeaders() {
        EntityExchangeResult<String> result = client.get()
                .uri(PING)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.service(), "Bearer " + TOKENS.user("alice"))
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertUnauthenticated(result);
    }

    @Test
    void rejectsExpiredToken() {
        String token = TOKENS.user("alice");
        clock.advance(Duration.ofSeconds(300 + 30 + 60));

        assertUnauthenticated(client.get()
                .uri(PING)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectBody(String.class)
                .returnResult());
    }

    @Test
    void acceptsValidUserToken() {
        client.get()
                .uri(PING)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.user("alice"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data")
                .isEqualTo("pong");
    }

    @Test
    void acceptsValidServiceClientToken() {
        client.get()
                .uri(PING)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.service())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data")
                .isEqualTo("pong");
    }

    private static void assertUnauthenticated(EntityExchangeResult<String> result) {
        JsonNode body = assertProblem(result, 401, "unauthenticated", PING);
        assertThat(result.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE))
                .containsExactly("Bearer");
        DETAILS.add(body.get("detail").asString());
    }
}
