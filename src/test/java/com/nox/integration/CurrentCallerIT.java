package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.KeycloakContainerSupport;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** US3 scenarios 1–3, 5 and 6: {@code GET /api/v1/me} describes exactly the caller of the token. */
class CurrentCallerIT extends KeycloakContainerSupport {

    private static final String ME = "/api/v1/me";
    private static final Set<String> CALLER_FIELDS =
            Set.of("subjectId", "callerType", "username", "clientId", "email", "roles");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void describesUserWithRoles() throws Exception {
        String token = TOKENS.user("alice");

        JsonNode data = me(token).get("data");

        assertThat(data.get("subjectId").asString()).isEqualTo(subjectOf(token));
        assertThat(data.get("callerType").asString()).isEqualTo("USER");
        assertThat(data.get("username").asString()).isEqualTo("alice");
        assertThat(data.get("clientId").isNull()).isTrue();
        assertThat(data.get("email").asString()).isEqualTo("alice@example.test");
        assertThat(data.get("roles").valueStream().map(JsonNode::asString)).contains("nox-tester", "nox-api-admin");
    }

    @Test
    void describesUserWithoutEmailOrRoles() throws Exception {
        String token = TOKENS.user("bob");

        JsonNode data = me(token).get("data");

        assertThat(data.get("subjectId").asString()).isEqualTo(subjectOf(token));
        assertThat(data.get("username").asString()).isEqualTo("bob");
        assertThat(data.get("email").isNull()).isTrue();
        assertThat(data.get("roles").isEmpty()).isTrue();
    }

    @Test
    void describesServiceClient() throws Exception {
        String token = TOKENS.service();

        JsonNode data = me(token).get("data");

        assertThat(data.get("subjectId").asString()).isEqualTo(subjectOf(token));
        assertThat(data.get("callerType").asString()).isEqualTo("SERVICE");
        assertThat(data.get("clientId").asString()).isEqualTo("nox-test-client");
        assertThat(data.get("username").isNull()).isTrue();
        assertThat(data.get("email").isNull()).isTrue();
    }

    @Test
    void ignoresSpoofedIdentityHeader() throws Exception {
        String token = TOKENS.user("alice");

        String body = client.get()
                .uri(ME)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "someone-else")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(jsonMapper.readTree(body).get("data").get("subjectId").asString())
                .isEqualTo(subjectOf(token));
    }

    @Test
    void rejectsRequestWithoutToken() {
        assertProblem(
                client.get().uri(ME).exchange().expectBody(String.class).returnResult(), 401, "unauthenticated", ME);
    }

    private JsonNode me(String token) {
        String body = client.get()
                .uri(ME)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        JsonNode response = jsonMapper.readTree(body);
        ContractAssertions.assertMatchesSchema(response, "CurrentCallerResponse");
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("data", "meta");
        assertThat(response.get("data").propertyNames()).containsExactlyInAnyOrderElementsOf(CALLER_FIELDS);
        assertThat(response.get("meta").get("traceId").asString()).matches("^[0-9a-f]{32}$");
        assertThat(response.get("meta").get("instance").asString()).isEqualTo(ME);
        assertThat(response.get("meta").get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        return response;
    }

    private static String subjectOf(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getSubject();
    }
}
