package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partner.Partner;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.PartnerFixtures;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * research.md decision 6: partner-facing endpoints accept only the partner BFF's token with a
 * callable {@code x-service-id}; user-facing endpoints accept only the internal BFF's token (004).
 */
class PartnerLinkSecurityRulesIT extends KeycloakContainerSupport {

    private static final String SERVICE_ID = "x-service-id";
    private static final String REQUESTS = "/api/v1/partner-link-requests";
    private static final String USER_ID = "X-User-ID";
    private static final String USER_LINKS = "/api/v1/user-partner-links";

    @Autowired
    private JdbcClient jdbc;

    private PartnerFixtures partners;
    private Partner active;

    @BeforeEach
    void createPartner() {
        partners = new PartnerFixtures(jdbc);
        active = partners.active();
    }

    static Stream<Arguments> partnerFacingPaths() {
        return Stream.of(
                Arguments.of(REQUESTS + "/" + UUID.randomUUID()),
                Arguments.of("/api/v1/partner-links/" + UUID.randomUUID()),
                Arguments.of("/api/v1/partner-links/by-external/external-1"));
    }

    @ParameterizedTest
    @MethodSource("partnerFacingPaths")
    void partnerFacingRejectsAServiceWithoutTheBffRole(String path) {
        assertProblem(get(path, TOKENS.service(), active.clientId()), 403, "forbidden", path);
    }

    @ParameterizedTest
    @MethodSource("partnerFacingPaths")
    void partnerFacingRejectsAUser(String path) {
        assertProblem(get(path, TOKENS.user("alice"), active.clientId()), 403, "forbidden", path);
    }

    @Test
    void partnerFacingPostRejectsAServiceWithoutTheBffRole() {
        EntityExchangeResult<String> result = client.post()
                .uri(REQUESTS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.service())
                .header(SERVICE_ID, active.clientId())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"externalId\":\"e\",\"email\":\"user@example.test\",\"licenseeId\":\"l\"}")
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertProblem(result, 403, "forbidden", REQUESTS);
    }

    static Stream<Arguments> nonCallableServiceIds() {
        return Stream.of(
                Arguments.of("missing", (Supplier<String>) () -> null),
                Arguments.of("unknown", (Supplier<String>) () -> "no-such-partner"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonCallableServiceIds")
    void bffWithoutACallablePartnerIsForbidden(String name, Supplier<String> serviceId) {
        String path = REQUESTS + "/" + UUID.randomUUID();

        assertProblem(get(path, TOKENS.bff(), serviceId.get()), 403, "forbidden", path);
    }

    @Test
    void bffForAnInactiveOrOutOfValidityPartnerIsForbidden() {
        String path = REQUESTS + "/" + UUID.randomUUID();

        for (Partner partner : new Partner[] {
            partners.suspended(), partners.deregistered(), partners.pastValidity(), partners.futureValidity()
        }) {
            assertProblem(get(path, TOKENS.bff(), partner.clientId()), 403, "forbidden", path);
        }
    }

    @Test
    void bffForACallablePartnerPassesSecurity() {
        String path = REQUESTS + "/" + UUID.randomUUID();

        assertThat(get(path, TOKENS.bff(), active.clientId()).getStatus().value())
                .isNotEqualTo(403);
    }

    @Test
    void userFacingRejectsAnythingButTheInternalBff() {
        String userId = UUID.randomUUID().toString();
        String detail = USER_LINKS + "/" + UUID.randomUUID();
        for (String path : new String[] {USER_LINKS, detail}) {
            assertProblem(get(path, TOKENS.service(), null, userId), 403, "forbidden", path);
            assertProblem(get(path, TOKENS.bff(), active.clientId(), userId), 403, "forbidden", path);
            assertProblem(get(path, TOKENS.user("alice"), null, userId), 403, "forbidden", path);
            assertProblem(get(path, TOKENS.user("bob"), null, userId), 403, "forbidden", path);
        }
    }

    @Test
    void userFacingAcceptsTheInternalBff() {
        assertThat(get(USER_LINKS, TOKENS.internalBff(), null, UUID.randomUUID().toString())
                        .getStatus()
                        .value())
                .isEqualTo(200);
    }

    private EntityExchangeResult<String> get(String path, String token, String serviceId) {
        return get(path, token, serviceId, null);
    }

    private EntityExchangeResult<String> get(String path, String token, String serviceId, String userId) {
        var request = client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (serviceId != null) {
            request = request.header(SERVICE_ID, serviceId);
        }
        if (userId != null) {
            request = request.header(USER_ID, userId);
        }
        return request.exchange().expectBody(String.class).returnResult();
    }
}
