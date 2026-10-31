package com.nox.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.support.CapturedLogs;
import com.nox.support.TestTracers;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BearerFailureHandlerTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ProblemResponseWriter writer = new ProblemResponseWriter(
            new MetaFactory(TestTracers.withTraceId("4bf92f3577b34da6a3ce929d0e0e4736"), Clock.systemUTC()),
            jsonMapper);
    private final ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(writer);
    private final BearerFailureHandler handler = new BearerFailureHandler(entryPoint, writer);
    private final CapturedLogs logs = CapturedLogs.of(SecurityEvents.class);

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    @Test
    void invalidTokenIsUnauthenticatedWithPlainBearerChallenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request(), response, invalidToken("Jwt expired at 2026-10-25T09:00:00Z"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("urn:problem:unauthenticated");
        assertSecurityEvent("authentication failed", "token_expired");
    }

    @Test
    void keySourceFailureIsIdentityProviderUnavailable() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationServiceException failure = new AuthenticationServiceException(
                "An error occurred while attempting to decode the Jwt: Signing keys unavailable",
                new JwtException("Signing keys unavailable"));

        handler.onAuthenticationFailure(request(), response, failure);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("urn:problem:identity_provider_unavailable");
        assertSecurityEvent("identity provider unavailable", "jwks_unavailable");
    }

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request(), response, new InsufficientAuthenticationException("Full authentication"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertSecurityEvent("authentication failed", "missing_token");
    }

    static Stream<Arguments> reasons() {
        return Stream.of(
                Arguments.of(invalidToken("Jwt expired at 2026-10-25T09:00:00Z"), "token_expired"),
                Arguments.of(invalidToken("Jwt used before 2026-10-25T09:00:00Z"), "token_not_yet_valid"),
                Arguments.of(invalidToken("The iss claim is not valid"), "invalid_issuer"),
                Arguments.of(invalidToken("The aud claim is not valid"), "invalid_audience"),
                Arguments.of(
                        new InvalidBearerTokenException(
                                "An error occurred while attempting to decode the Jwt: "
                                        + "Signed JWT rejected: Invalid signature",
                                new BadJwtException("Invalid signature")),
                        "invalid_signature"),
                Arguments.of(
                        new InvalidBearerTokenException(
                                "An error occurred while attempting to decode the Jwt: " + "Malformed token",
                                new BadJwtException("Malformed token")),
                        "malformed_token"),
                Arguments.of(
                        new InvalidBearerTokenException(
                                "Unsupported algorithm of none", new BadJwtException("Unsupported algorithm of none")),
                        "invalid_signature"),
                Arguments.of(new InvalidBearerTokenException("Bearer token is malformed"), "malformed_token"));
    }

    @ParameterizedTest
    @MethodSource("reasons")
    void derivesReasonFromException(AuthenticationException failure, String reason) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request(), response, failure);

        assertThat(response.getStatus()).isEqualTo(401);
        assertSecurityEvent("authentication failed", reason);
    }

    @Test
    void detailIsIdenticalForEveryInvalidTokenReason() throws Exception {
        String expired = detailFor(invalidToken("Jwt expired at 2026-10-25T09:00:00Z"));
        String audience = detailFor(invalidToken("The aud claim is not valid"));

        assertThat(expired)
                .isEqualTo(audience)
                .doesNotContainIgnoringCase("aud")
                .doesNotContainIgnoringCase("expired");
    }

    private String detailFor(AuthenticationException failure) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request(), response, failure);
        return jsonMapper.readTree(response.getContentAsString()).get("detail").asString();
    }

    private void assertSecurityEvent(String message, String reason) {
        List<ILoggingEvent> events = logs.events();
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo(message);
        assertThat(CapturedLogs.keyValue(event, "reason")).isEqualTo(reason);
        assertThat(logs.allText()).doesNotContain(TOKEN).doesNotContain("eyJ");
    }

    private static InvalidBearerTokenException invalidToken(String validationError) {
        JwtValidationException cause = new JwtValidationException(
                validationError, List.of(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, validationError, null)));
        return new InvalidBearerTokenException(validationError, cause);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }
}
