package com.nox.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class JwtCallerIdentityMapperTest {

    private final JwtCallerIdentityMapper mapper = new JwtCallerIdentityMapper(new KeycloakRoleExtractor("nox-api"));

    @Test
    void mapsUserToken() {
        CallerIdentity caller = mapper.map(jwt(Map.of(
                "sub", "user-1",
                "preferred_username", "alice",
                "email", "alice@example.test",
                "azp", "nox-test-client",
                "realm_access", Map.of("roles", List.of("nox-tester")),
                "resource_access", Map.of("nox-api", Map.of("roles", List.of("nox-api-admin"))))));

        assertThat(caller.subjectId()).isEqualTo("user-1");
        assertThat(caller.callerType()).isEqualTo(CallerType.USER);
        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.clientId()).isNull();
        assertThat(caller.email()).isEqualTo("alice@example.test");
        assertThat(caller.roles()).containsExactly("nox-api-admin", "nox-tester");
    }

    @Test
    void mapsServiceTokenWithClientIdClaim() {
        CallerIdentity caller = mapper.map(jwt(Map.of(
                "sub", "service-1",
                "client_id", "nox-test-client",
                "preferred_username", "service-account-nox-test-client",
                "azp", "nox-test-client")));

        assertThat(caller.callerType()).isEqualTo(CallerType.SERVICE);
        assertThat(caller.clientId()).isEqualTo("nox-test-client");
        assertThat(caller.username()).isNull();
        assertThat(caller.email()).isNull();
        assertThat(caller.roles()).isEmpty();
    }

    @Test
    void detectsServiceAccountByUsernamePrefixAndFallsBackToAzp() {
        CallerIdentity caller = mapper.map(jwt(Map.of(
                "sub", "service-2",
                "preferred_username", "service-account-batch-job",
                "azp", "batch-job")));

        assertThat(caller.callerType()).isEqualTo(CallerType.SERVICE);
        assertThat(caller.clientId()).isEqualTo("batch-job");
        assertThat(caller.username()).isNull();
    }

    @Test
    void missingEmailIsNull() {
        CallerIdentity caller = mapper.map(jwt(Map.of("sub", "user-2", "preferred_username", "bob")));

        assertThat(caller.email()).isNull();
        assertThat(caller.roles()).isEmpty();
    }

    @Test
    void tokenWithoutIdentityClaimsIsRejectedAsInvalid() {
        assertThatThrownBy(() -> mapper.map(jwt(Map.of("sub", "user-3"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
