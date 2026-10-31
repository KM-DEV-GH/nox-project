package com.nox.integration;

import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.EVENTS_EXCHANGE;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.REJECTED;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.WEBHOOKS_EXCHANGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** US2, FR-007, FR-011, SC-006: rejecting notifies the user and the partner, without a link id for the partner. */
class PartnerLinkRejectionIT extends PartnerLinkIntegrationSupport {

    private static final Duration DELIVERY = Duration.ofSeconds(5);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void rejectionRecordsItAndNotifiesBothAudiences() {
        String userMessages = queues.bind(EVENTS_EXCHANGE, REJECTED);
        String partnerMessages = queues.bind(WEBHOOKS_EXCHANGE, REJECTED);
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner);

        EntityExchangeResult<String> rejected = api.postAsInternalBff(link.userId(), link.path() + "/reject");

        assertThat(rejected.getStatus().value()).isEqualTo(200);
        JsonNode body = PartnerLinkApi.json(rejected);
        ContractAssertions.assertMatchesSchema(body, "UserLinkResponse");
        assertThat(body.get("data").get("status").asString()).isEqualTo("REJECTED");
        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.REJECTED);
        assertThat(history.findByLinkId(link.linkId()))
                .extracting(PartnerLinkHistoryEntry::reason)
                .containsExactly(TransitionReason.LINK_CREATED, TransitionReason.USER_REJECTED);

        JsonNode toUser = data(queues.awaitFirst(userMessages, mentioning(link.linkId()), DELIVERY));
        assertThat(toUser.propertyNames()).containsExactlyInAnyOrder("linkId", "userId", "partnerName", "occurredAt");

        JsonNode toPartner = data(queues.awaitFirst(partnerMessages, mentioning(link.requestId()), DELIVERY));
        assertThat(toPartner.propertyNames())
                .containsExactlyInAnyOrder("partnerClientId", "requestId", "externalId", "status", "occurredAt");
        assertThat(toPartner.has("linkId")).isFalse();
        assertThat(toPartner.get("status").asString()).isEqualTo("REJECTED");
        assertThat(toPartner.get("externalId").asString()).isEqualTo(link.externalId());
    }

    private JsonNode data(Message message) {
        return jsonMapper
                .readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                .get("data");
    }
}
