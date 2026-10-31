package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.TestOnlyPingController;
import com.nox.integration.support.TestOnlyRoleController;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SC-005, FR-012, US3 scenario 4 and Principle VII: what the service logs while handling requests. */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIT extends KeycloakContainerSupport {

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void logsSecurityEventsWithContextAndNeverCredentials(CapturedOutput output) throws Exception {
        String alice = TOKENS.user("alice");
        String bob = TOKENS.user("bob");
        String expired = TOKENS.user("alice");

        get("/api/v1/me", alice);
        String failureTraceId = traceIdOf(get(TestOnlyPingController.BOOM, alice));
        get(TestOnlyPingController.PING, TOKENS.otherApp());
        String forbiddenTraceId = traceIdOf(get(TestOnlyRoleController.TESTER_ONLY, bob));
        clock.advance(Duration.ofMinutes(10));
        get(TestOnlyPingController.PING, expired);

        String logs = output.getAll();
        assertThat(JWT.matcher(logs).find()).as("JWT-shaped string in logs").isFalse();
        assertThat(logs).doesNotContain(alice).doesNotContain(bob).doesNotContain(expired);

        List<JsonNode> entries = jsonLines(logs);
        JsonNode audienceFailure = single(entries, "authentication failed", "invalid_audience");
        JsonNode expiryFailure = single(entries, "authentication failed", "token_expired");
        JsonNode denied = single(entries, "access denied", "missing_role");
        JsonNode unexpected = entries.stream()
                .filter(entry -> "unexpected error".equals(text(entry, "message")))
                .findFirst()
                .orElseThrow();

        assertThat(audienceFailure.get("request").get("url").asString()).isEqualTo(TestOnlyPingController.PING);
        assertThat(expiryFailure.get("trace").get("traceId").asString()).matches("^[0-9a-f]{32}$");
        assertThat(denied.get("user").get("id").asString()).isEqualTo(subjectOf(bob));
        assertThat(denied.get("trace").get("traceId").asString()).isEqualTo(forbiddenTraceId);
        assertThat(unexpected.get("user").get("id").asString()).isEqualTo(subjectOf(alice));
        assertThat(unexpected.get("trace").get("traceId").asString()).isEqualTo(failureTraceId);
    }

    private String get(String path, String token) {
        return client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String traceIdOf(String problemBody) {
        return jsonMapper.readTree(problemBody).get("traceId").asString();
    }

    private List<JsonNode> jsonLines(String logs) {
        return logs.lines()
                .filter(line -> line.startsWith("{"))
                .map(jsonMapper::readTree)
                .toList();
    }

    private static JsonNode single(List<JsonNode> entries, String message, String reason) {
        List<JsonNode> matches = entries.stream()
                .filter(entry -> message.equals(text(entry, "message")) && reason.equals(text(entry, "reason")))
                .toList();
        assertThat(matches).as("%s / %s", message, reason).hasSize(1);
        assertThat(matches.getFirst().get("level").asString()).isEqualTo("WARN");
        return matches.getFirst();
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null ? null : value.asString();
    }

    private static String subjectOf(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getSubject();
    }
}
