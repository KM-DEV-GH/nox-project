package com.nox.integration;

import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.APPROVED;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.EVENTS_EXCHANGE;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.WEBHOOKS_EXCHANGE;
import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** US2, FR-007, FR-011, SC-006: approving connects the link and notifies the user and the partner. */
class PartnerLinkApprovalIT extends PartnerLinkIntegrationSupport {

    /** SC-006: broker-confirmed within 5 s of the commit. */
    private static final Duration DELIVERY = Duration.ofSeconds(5);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void approvalConnectsTheLinkRecordsItAndNotifiesBothAudiences() {
        String userMessages = queues.bind(EVENTS_EXCHANGE, APPROVED);
        String partnerMessages = queues.bind(WEBHOOKS_EXCHANGE, APPROVED);
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner);

        EntityExchangeResult<String> approved = api.postAsInternalBff(link.userId(), link.path() + "/approve");

        assertThat(approved.getStatus().value()).isEqualTo(200);
        JsonNode body = PartnerLinkApi.json(approved);
        ContractAssertions.assertMatchesSchema(body, "UserLinkResponse");
        assertThat(body.get("data").get("status").asString()).isEqualTo("CONNECTED");
        assertThat(body.get("data").get("partnerName").asString()).isEqualTo(partner.name());
        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.CONNECTED);
        assertThat(history.findByLinkId(link.linkId()))
                .extracting(PartnerLinkHistoryEntry::reason)
                .containsExactly(TransitionReason.LINK_CREATED, TransitionReason.USER_APPROVED);
        assertThat(history.findByLinkId(link.linkId()).getLast().actorType()).isEqualTo(ActorType.USER);

        JsonNode toUser = data(queues.awaitFirst(userMessages, mentioning(link.linkId()), DELIVERY));
        assertThat(toUser.propertyNames()).containsExactlyInAnyOrder("linkId", "userId", "partnerName", "occurredAt");
        assertThat(toUser.get("userId").asString()).isEqualTo(link.userId());
        assertThat(toUser.get("partnerName").asString()).isEqualTo(partner.name());

        Message webhook = queues.awaitFirst(partnerMessages, mentioning(link.linkId()), DELIVERY);
        assertThat(webhook.getMessageProperties().getCorrelationId())
                .isEqualTo(link.correlationId().toString());
        assertThat(webhook.getMessageProperties().getHeaders()).containsKey("traceId");
        JsonNode toPartner = data(webhook);
        assertThat(toPartner.propertyNames())
                .containsExactlyInAnyOrder(
                        "partnerClientId", "requestId", "externalId", "linkId", "status", "occurredAt");
        assertThat(toPartner.get("partnerClientId").asString()).isEqualTo(partner.clientId());
        assertThat(toPartner.get("requestId").asString())
                .isEqualTo(link.requestId().toString());
        assertThat(toPartner.get("externalId").asString()).isEqualTo(link.externalId());
        assertThat(toPartner.get("status").asString()).isEqualTo("CONNECTED");
    }

    @Test
    void approvingTwiceIsNotActionable() {
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner);
        api.postAsInternalBff(link.userId(), link.path() + "/approve");

        EntityExchangeResult<String> again = api.postAsInternalBff(link.userId(), link.path() + "/approve");

        assertProblem(again, 409, "link_not_actionable", link.path() + "/approve");
        assertThat(history.findByLinkId(link.linkId())).hasSize(2);
    }

    @Test
    void anotherUsersLinkIsNotFound() {
        Link link = scenarios.pendingLink(partner);

        EntityExchangeResult<String> result =
                api.postAsInternalBff(UUID.randomUUID().toString(), link.path() + "/approve");

        assertProblem(result, 404, "not_found", link.path() + "/approve");
        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.PENDING);
    }

    private JsonNode data(Message message) {
        return jsonMapper
                .readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                .get("data");
    }
}
