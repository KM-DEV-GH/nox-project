package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import com.nox.integration.support.TestTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** US3, FR-012–FR-014, SC-002, SC-003: what partners and users can read, and that it reveals nothing. */
class PartnerLinkStatusQueryIT extends PartnerLinkIntegrationSupport {

    private static final Duration PAST_DEADLINE = Duration.ofMinutes(2).plusSeconds(1);

    @Test
    void knownAndUnknownEmailsReportTheSameStatusBeforeAndAfterTheDeadline() {
        Link known = scenarios.pendingLink(partner, TestTokens.subjectOf(TOKENS.user("alice")));
        PartnerLinkRequest unknown = scenarios.unknownEmailRequest(partner);

        assertSameStatus(known.requestId(), unknown.id(), "PROCESSING");
        clock.advance(PAST_DEADLINE);
        assertSameStatus(known.requestId(), unknown.id(), "EXPIRED");
    }

    @Test
    void theRequestReportsTheUsersDecision() {
        String alice = TOKENS.user("alice");
        Link approved = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        Link rejected = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));

        api.postAsInternalBff(approved.userId(), approved.path() + "/approve");
        api.postAsInternalBff(rejected.userId(), rejected.path() + "/reject");

        assertThat(requestStatus(approved.requestId())).isEqualTo("CONNECTED");
        assertThat(requestStatus(rejected.requestId())).isEqualTo("REJECTED");
    }

    @Test
    void partnersReadOnlyEstablishedLinks() {
        String alice = TOKENS.user("alice");
        Link pending = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        Link rejected = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        Link connected = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        api.postAsInternalBff(rejected.userId(), rejected.path() + "/reject");
        api.postAsInternalBff(connected.userId(), connected.path() + "/approve");

        EntityExchangeResult<String> read =
                api.getAsPartner(partner, PartnerLinkApi.PARTNER_LINKS + "/" + connected.linkId());
        assertThat(read.getStatus().value()).isEqualTo(200);
        JsonNode body = PartnerLinkApi.json(read);
        ContractAssertions.assertMatchesSchema(body, "PartnerLinkResponse");
        assertThat(body.get("data").get("externalId").asString()).isEqualTo(connected.externalId());
        assertThat(body.get("data").get("status").asString()).isEqualTo("CONNECTED");

        for (Link hidden : List.of(pending, rejected)) {
            assertNotFound(partner, PartnerLinkApi.PARTNER_LINKS + "/" + hidden.linkId());
            assertNotFound(partner, PartnerLinkApi.PARTNER_LINKS + "/by-external/" + hidden.externalId());
        }
        Link expiring = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        clock.advance(PAST_DEADLINE);
        api.postAsInternalBff(expiring.userId(), expiring.path() + "/approve");
        assertNotFound(partner, PartnerLinkApi.PARTNER_LINKS + "/" + expiring.linkId());
    }

    @Test
    void anotherPartnerReadsNothing() {
        String alice = TOKENS.user("alice");
        Link connected = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        api.postAsInternalBff(connected.userId(), connected.path() + "/approve");
        Partner other = partners.active();

        assertNotFound(other, PartnerLinkApi.PARTNER_LINKS + "/" + connected.linkId());
        assertNotFound(other, PartnerLinkApi.PARTNER_LINKS + "/by-external/" + connected.externalId());
        assertNotFound(other, PartnerLinkApi.REQUESTS + "/" + connected.requestId());
    }

    @Test
    void byExternalIdReturnsTheMostRecentlyCreatedLink() {
        String alice = TOKENS.user("alice");
        String externalId = "external-" + UUID.randomUUID();
        Link older = scenarios.pendingLink(partner, TestTokens.subjectOf(alice), externalId);
        Link newer = scenarios.pendingLink(partner, TestTokens.subjectOf(alice), externalId);
        api.postAsInternalBff(older.userId(), older.path() + "/approve");
        api.postAsInternalBff(newer.userId(), newer.path() + "/approve");

        JsonNode latest = PartnerLinkApi.json(
                api.getAsPartner(partner, PartnerLinkApi.PARTNER_LINKS + "/by-external/" + externalId));

        assertThat(latest.get("data").get("linkId").asString())
                .isEqualTo(newer.linkId().toString());
    }

    @Test
    void theUserSeesEveryOneOfTheirLinksWithItsCurrentStatus() {
        String aliceKeycloakId = TestTokens.subjectOf(TOKENS.user("alice"));
        String alice = UUID.randomUUID().toString();
        Link connected = scenarios.pendingLink(partner, aliceKeycloakId, "external-" + UUID.randomUUID(), alice);
        Link rejected =
                scenarios.pendingLink(partners.active(), aliceKeycloakId, "external-" + UUID.randomUUID(), alice);
        Link pending =
                scenarios.pendingLink(partners.active(), aliceKeycloakId, "external-" + UUID.randomUUID(), alice);
        api.postAsInternalBff(alice, connected.path() + "/approve");
        api.postAsInternalBff(alice, rejected.path() + "/reject");

        Map<String, String> statuses = userLinks(alice);

        assertThat(statuses)
                .containsEntry(connected.linkId().toString(), "CONNECTED")
                .containsEntry(rejected.linkId().toString(), "REJECTED")
                .containsEntry(pending.linkId().toString(), "PENDING");
        clock.advance(PAST_DEADLINE);
        assertThat(userLinks(alice)).containsEntry(pending.linkId().toString(), "EXPIRED");
        JsonNode detail = PartnerLinkApi.json(api.getAsInternalBff(alice, pending.path()));
        ContractAssertions.assertMatchesSchema(detail, "UserLinkResponse");
        assertThat(detail.get("data").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(userLinks(UUID.randomUUID().toString()))
                .doesNotContainKey(pending.linkId().toString());
        assertProblem(
                api.getAsInternalBff(UUID.randomUUID().toString(), pending.path()), 404, "not_found", pending.path());
    }

    private void assertSameStatus(UUID knownRequest, UUID unknownRequest, String expected) {
        EntityExchangeResult<String> forKnown = api.getAsPartner(partner, PartnerLinkApi.REQUESTS + "/" + knownRequest);
        EntityExchangeResult<String> forUnknown =
                api.getAsPartner(partner, PartnerLinkApi.REQUESTS + "/" + unknownRequest);
        assertThat(forKnown.getStatus().value()).isEqualTo(200);
        assertThat(forUnknown.getStatus().value()).isEqualTo(200);
        JsonNode known = PartnerLinkApi.json(forKnown);
        JsonNode unknown = PartnerLinkApi.json(forUnknown);
        ContractAssertions.assertMatchesSchema(known, "LinkRequestStatusResponse");
        ContractAssertions.assertMatchesSchema(unknown, "LinkRequestStatusResponse");
        assertThat(known.get("data").get("status").asString()).isEqualTo(expected);
        assertThat(unknown.get("data").get("status").asString()).isEqualTo(expected);
        assertThat(unknown.get("data").propertyNames())
                .isEqualTo(known.get("data").propertyNames());
    }

    private String requestStatus(UUID requestId) {
        return PartnerLinkApi.json(api.getAsPartner(partner, PartnerLinkApi.REQUESTS + "/" + requestId))
                .get("data")
                .get("status")
                .asString();
    }

    private void assertNotFound(Partner caller, String path) {
        assertProblem(api.getAsPartner(caller, path), 404, "not_found", path);
    }

    private Map<String, String> userLinks(String userId) {
        JsonNode body = PartnerLinkApi.json(api.getAsInternalBff(userId, PartnerLinkApi.USER_LINKS));
        ContractAssertions.assertMatchesSchema(body, "UserLinkListResponse");
        return StreamSupport.stream(body.get("data").spliterator(), false)
                .collect(Collectors.toMap(
                        link -> link.get("linkId").asString(),
                        link -> link.get("status").asString()));
    }
}
