package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import com.nox.integration.support.TestTokens;
import com.nox.support.CapturedLogs;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The caller matrix of 004 contracts/access-control.md against every endpoint of the feature.
 * Partner-facing endpoints accept only the partner BFF ({@code partner-access}) calling for a
 * callable partner; user partner link endpoints accept only the internal BFF ({@code
 * internal-access}) calling for the user in {@code X-User-ID}. A user's own token reaches neither.
 */
class PartnerLinkAuthorizationIT extends PartnerLinkIntegrationSupport {

    private static final String RETIRED_PREFIX = "/api/v1/me/partner-links";
    private static final String SECURITY_EVENTS = "com.nox.adapter.in.web.security.SecurityEvents";

    private Link alicesLink;
    private List<Endpoint> partnerFacing;
    private List<Endpoint> userFacing;

    @BeforeEach
    void createLink() {
        alicesLink = scenarios.pendingLink(partner, TestTokens.subjectOf(TOKENS.user("alice")));
        partnerFacing = List.of(
                new Endpoint(HttpMethod.POST, PartnerLinkApi.REQUESTS),
                new Endpoint(HttpMethod.GET, PartnerLinkApi.REQUESTS + "/" + alicesLink.requestId()),
                new Endpoint(HttpMethod.GET, PartnerLinkApi.PARTNER_LINKS + "/" + alicesLink.linkId()),
                new Endpoint(HttpMethod.GET, PartnerLinkApi.PARTNER_LINKS + "/by-external/" + alicesLink.externalId()));
        userFacing = List.of(
                new Endpoint(HttpMethod.GET, PartnerLinkApi.USER_LINKS),
                new Endpoint(HttpMethod.GET, alicesLink.path()),
                new Endpoint(HttpMethod.GET, alicesLink.path() + "/history"),
                new Endpoint(HttpMethod.POST, alicesLink.path() + "/reject"),
                new Endpoint(HttpMethod.POST, alicesLink.path() + "/revoke"),
                new Endpoint(HttpMethod.POST, alicesLink.path() + "/approve"));
    }

    @Test
    void withoutATokenEverythingIsUnauthenticated() {
        for (Endpoint endpoint : all()) {
            assertProblem(call(endpoint, null, null, null), 401, "unauthenticated", endpoint.path());
        }
    }

    @Test
    void aTokenForAnotherAudienceIsUnauthenticated() {
        for (Endpoint endpoint : all()) {
            assertProblem(
                    call(endpoint, TOKENS.otherApp(), partner.clientId(), alicesLink.userId()),
                    401,
                    "unauthenticated",
                    endpoint.path());
        }
    }

    @Test
    void thePartnerBffForACallablePartnerReachesOnlyPartnerFacingEndpoints() {
        String bff = TOKENS.bff();
        for (Endpoint endpoint : partnerFacing) {
            assertThat(call(endpoint, bff, partner.clientId(), null).getStatus().value())
                    .as(endpoint.toString())
                    .isNotIn(401, 403);
        }
        for (Endpoint endpoint : userFacing) {
            assertProblem(
                    call(endpoint, bff, partner.clientId(), alicesLink.userId()), 403, "forbidden", endpoint.path());
        }
    }

    @Test
    void thePartnerBffForAPartnerThatMayNotCallIsForbidden() {
        String bff = TOKENS.bff();
        for (String serviceId : new String[] {
            null,
            "no-such-partner",
            partners.suspended().clientId(),
            partners.deregistered().clientId(),
            partners.pastValidity().clientId()
        }) {
            for (Endpoint endpoint : partnerFacing) {
                assertProblem(call(endpoint, bff, serviceId, null), 403, "forbidden", endpoint.path());
            }
        }
    }

    @Test
    void anotherServiceIsForbiddenEverywhere() {
        String service = TOKENS.service();
        for (Endpoint endpoint : all()) {
            assertProblem(
                    call(endpoint, service, partner.clientId(), alicesLink.userId()),
                    403,
                    "forbidden",
                    endpoint.path());
        }
    }

    @Test
    void theInternalBffReachesOnlyUserFacingEndpointsAndOnlyTheActedForUsersLinks() {
        String internalBff = TOKENS.internalBff();
        String someoneElse = UUID.randomUUID().toString();
        for (Endpoint endpoint : partnerFacing) {
            assertProblem(
                    call(endpoint, internalBff, partner.clientId(), alicesLink.userId()),
                    403,
                    "forbidden",
                    endpoint.path());
        }
        assertThat(call(userFacing.getFirst(), internalBff, null, someoneElse)
                        .getStatus()
                        .value())
                .isEqualTo(200);
        for (Endpoint endpoint : userFacing.subList(1, userFacing.size())) {
            assertProblem(call(endpoint, internalBff, null, someoneElse), 404, "not_found", endpoint.path());
        }
        for (Endpoint endpoint : userFacing) {
            assertThat(call(endpoint, internalBff, null, alicesLink.userId())
                            .getStatus()
                            .value())
                    .as(endpoint.toString())
                    .isNotIn(400, 401, 403, 404);
        }
    }

    @Test
    void theInternalBffWithoutAValidUserIdIsABadRequest() {
        String internalBff = TOKENS.internalBff();
        for (String userId : new String[] {null, "", "not-a-uuid", "1-1-1-1-1"}) {
            for (Endpoint endpoint : userFacing) {
                assertProblem(call(endpoint, internalBff, null, userId), 400, "bad_request", endpoint.path());
            }
        }
        assertThat(links.findById(alicesLink.linkId()).orElseThrow().status().name())
                .isEqualTo("PENDING");
    }

    /** Story 3: alice's token carries both {@code partner-access} and {@code internal-access}. */
    @Test
    void aUsersOwnTokenIsForbiddenEverywhereWhateverItsRoles() {
        for (String user : new String[] {TOKENS.user("alice"), TOKENS.user("bob")}) {
            for (Endpoint endpoint : all()) {
                assertProblem(
                        call(endpoint, user, partner.clientId(), alicesLink.userId()),
                        403,
                        "forbidden",
                        endpoint.path());
                assertProblem(call(endpoint, user, null, null), 403, "forbidden", endpoint.path());
            }
        }
    }

    /** FR-013: every refusal on every surface is recorded once, without the token. */
    @Test
    void everyRefusalIsAudited() {
        String alice = TOKENS.user("alice");
        Endpoint[] refused = {
            partnerFacing.get(1), // link requests
            partnerFacing.get(2), // link queries
            userFacing.get(1) // user links
        };
        for (Endpoint endpoint : refused) {
            try (CapturedLogs logs = CapturedLogs.of(SECURITY_EVENTS)) {
                assertProblem(
                        call(endpoint, alice, partner.clientId(), alicesLink.userId()),
                        403,
                        "forbidden",
                        endpoint.path());

                assertThat(logs.events())
                        .as(endpoint.toString())
                        .singleElement()
                        .satisfies(event -> {
                            assertThat(event.getFormattedMessage()).isEqualTo("access denied");
                            assertThat(CapturedLogs.keyValue(event, "reason")).isEqualTo("missing_role");
                        });
                assertThat(logs.allText()).doesNotContain(alice);
            }
        }
    }

    /** FR-019: the user partner link endpoints moved; nothing answers on the old prefix. */
    @Test
    void theRetiredPrefixIsGone() {
        String internalBff = TOKENS.internalBff();
        String linkPath = RETIRED_PREFIX + "/" + alicesLink.linkId();
        for (Endpoint endpoint : List.of(
                new Endpoint(HttpMethod.GET, RETIRED_PREFIX),
                new Endpoint(HttpMethod.GET, linkPath),
                new Endpoint(HttpMethod.GET, linkPath + "/history"),
                new Endpoint(HttpMethod.POST, linkPath + "/approve"),
                new Endpoint(HttpMethod.POST, linkPath + "/reject"),
                new Endpoint(HttpMethod.POST, linkPath + "/revoke"))) {
            assertProblem(call(endpoint, null, null, null), 401, "unauthenticated", endpoint.path());
            assertProblem(call(endpoint, internalBff, null, alicesLink.userId()), 404, "not_found", endpoint.path());
        }
        assertThat(links.findById(alicesLink.linkId()).orElseThrow().status().name())
                .isEqualTo("PENDING");
    }

    private List<Endpoint> all() {
        return Stream.concat(partnerFacing.stream(), userFacing.stream()).toList();
    }

    private EntityExchangeResult<String> call(Endpoint endpoint, String token, String serviceId, String userId) {
        RestTestClient.RequestBodySpec request =
                client.method(endpoint.method()).uri(endpoint.path());
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (serviceId != null) {
            request = request.header("x-service-id", serviceId);
        }
        if (userId != null) {
            request = request.header(PartnerLinkApi.USER_ID_HEADER, userId);
        }
        RestTestClient.RequestHeadersSpec<?> ready = !endpoint.path().equals(PartnerLinkApi.REQUESTS)
                ? request
                : request.header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"externalId\":\"e\",\"email\":\"user@example.test\",\"licenseeId\":\"l\"}");
        return ready.exchange().expectBody(String.class).returnResult();
    }

    private record Endpoint(HttpMethod method, String path) {}
}
