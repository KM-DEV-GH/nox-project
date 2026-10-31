package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import com.nox.integration.support.TestTokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** US4, FR-015, FR-016, SC-004: the user ends a connection; nothing is published. */
class PartnerLinkRevocationIT extends PartnerLinkIntegrationSupport {

    @Test
    void revokingAConnectedLinkRecordsItSilently() {
        String userMessages = queues.bind("partnerlink.events", "#");
        String partnerMessages = queues.bind("partnerlink.webhooks", "#");
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        api.postAsInternalBff(link.userId(), link.path() + "/approve");
        queues.collect(userMessages, message -> true, Duration.ofSeconds(1));
        queues.collect(partnerMessages, message -> true, Duration.ofSeconds(1));

        EntityExchangeResult<String> revoked = api.postAsInternalBff(link.userId(), link.path() + "/revoke");

        assertThat(revoked.getStatus().value()).isEqualTo(200);
        JsonNode body = PartnerLinkApi.json(revoked);
        ContractAssertions.assertMatchesSchema(body, "UserLinkResponse");
        assertThat(body.get("data").get("status").asString()).isEqualTo("REVOKED");
        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.REVOKED);
        assertThat(history.findByLinkId(link.linkId()))
                .extracting(PartnerLinkHistoryEntry::reason)
                .containsExactly(
                        TransitionReason.LINK_CREATED, TransitionReason.USER_APPROVED, TransitionReason.USER_REVOKED);
        assertThat(queues.collect(userMessages, mentioning(link.linkId()), Duration.ofSeconds(2)))
                .isEmpty();
        assertThat(queues.collect(partnerMessages, mentioning(link.requestId()), Duration.ofSeconds(1)))
                .isEmpty();

        JsonNode partnerView =
                PartnerLinkApi.json(api.getAsPartner(partner, PartnerLinkApi.PARTNER_LINKS + "/" + link.linkId()));
        assertThat(partnerView.get("data").get("status").asString()).isEqualTo("REVOKED");
        JsonNode requestView =
                PartnerLinkApi.json(api.getAsPartner(partner, PartnerLinkApi.REQUESTS + "/" + link.requestId()));
        assertThat(requestView.get("data").get("status").asString()).isEqualTo("REVOKED");
    }

    @Test
    void revokingTwiceOrAnUndecidedLinkIsNotActionable() {
        String alice = TOKENS.user("alice");
        Link connected = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        Link pending = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        api.postAsInternalBff(connected.userId(), connected.path() + "/approve");
        api.postAsInternalBff(connected.userId(), connected.path() + "/revoke");

        assertProblem(
                api.postAsInternalBff(connected.userId(), connected.path() + "/revoke"),
                409,
                "link_not_actionable",
                connected.path() + "/revoke");
        assertProblem(
                api.postAsInternalBff(pending.userId(), pending.path() + "/revoke"),
                409,
                "link_not_actionable",
                pending.path() + "/revoke");
        assertThat(history.findByLinkId(connected.linkId())).hasSize(3);
        assertThat(links.findById(pending.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.PENDING);
    }
}
