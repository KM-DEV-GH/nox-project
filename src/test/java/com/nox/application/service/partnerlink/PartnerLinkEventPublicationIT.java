package com.nox.application.service.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.application.event.PartnerLinkApprovedForPartner;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.InfrastructureContainers;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.NoxKeycloakContainer;
import com.nox.integration.support.PartnerFixtures;
import com.nox.integration.support.TestQueues;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Principle XI and research.md decision 3a: messages leave through Spring Modulith's event
 * publication registry only after the transaction commits, carry the required properties, and
 * survive a broker outage.
 */
class PartnerLinkEventPublicationIT extends KeycloakContainerSupport {

    private static final String WEBHOOKS = "partnerlink.webhooks";
    private static final String EVENTS = "partnerlink.events";
    private static final String APPROVED = "partner-link.approved.v1";
    private static final String TRACE_ID = "a0b3dd10e3be3097d588b6c3ccd7f4c0";
    private static final Duration DELIVERY = Duration.ofSeconds(10);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private PartnerLinkTransition transition;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IncompleteEventPublications incompletePublications;

    private TestQueues queues;
    private Partner partner;

    @BeforeEach
    void setUp(@Autowired AmqpAdmin admin, @Autowired RabbitTemplate rabbit) {
        queues = new TestQueues(admin, rabbit);
        partner = new PartnerFixtures(jdbc).active();
    }

    @Test
    void rolledBackTransitionPublishesNothing() {
        PartnerLink link = approvedLink();
        PartnerLinkApprovedForPartner event = approvedEvent(link);

        transactions.executeWithoutResult(status -> {
            transition.record(link, approval(link), List.of(event));
            status.setRollbackOnly();
        });

        assertThat(publicationsOf(event.messageId())).isZero();
        assertThat(jdbc.sql("select count(*) from partner_link where id = :id")
                        .param("id", link.id())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void committedTransitionIsDeliveredOnceWithTheRequiredProperties() {
        String webhooks = queues.bind(WEBHOOKS, APPROVED);
        String events = queues.bind(EVENTS, "#");
        PartnerLink link = approvedLink();
        PartnerLinkApprovedForPartner event = approvedEvent(link);

        transition.record(link, approval(link), List.of(event));

        Message message = queues.awaitFirst(webhooks, TestQueues.withMessageId(event.messageId()), DELIVERY);
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getCorrelationId())
                .isEqualTo(event.correlationId().toString());
        assertThat(properties.getType()).isEqualTo(APPROVED);
        assertThat(properties.getReceivedRoutingKey()).isEqualTo(APPROVED);
        assertThat(properties.getHeaders()).containsEntry("traceId", TRACE_ID);
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getHeaders()).doesNotContainKey("__TypeId__");

        JsonNode body = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("data", "meta");
        JsonNode data = body.get("data");
        assertThat(data.propertyNames())
                .containsExactlyInAnyOrder(
                        "partnerClientId", "requestId", "externalId", "linkId", "status", "occurredAt");
        assertThat(data.get("partnerClientId").asString()).isEqualTo(partner.clientId());
        assertThat(data.get("requestId").asString()).isEqualTo(event.requestId().toString());
        assertThat(data.get("externalId").asString()).isEqualTo(link.partnerExternalId());
        assertThat(data.get("linkId").asString()).isEqualTo(link.id().toString());
        assertThat(data.get("status").asString()).isEqualTo("CONNECTED");
        assertThat(Instant.parse(data.get("occurredAt").asString())).isEqualTo(event.occurredAt());
        JsonNode meta = body.get("meta");
        assertThat(meta.get("traceId").asString()).isEqualTo(TRACE_ID);
        assertThat(meta.get("instance").asString()).isEqualTo(APPROVED);
        assertThat(meta.get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

        assertThat(queues.collect(webhooks, TestQueues.withMessageId(event.messageId()), Duration.ofSeconds(2)))
                .as("no duplicate delivery")
                .isEmpty();
        assertThat(queues.collect(events, TestQueues.withMessageId(event.messageId()), Duration.ofSeconds(1)))
                .as("partner event never reaches the user exchange")
                .isEmpty();
        await().atMost(DELIVERY).until(() -> completedPublicationsOf(event.messageId()) == 1);
    }

    @Test
    void messageIsDeliveredAfterTheBrokerRecovers() {
        String webhooks = queues.bind(WEBHOOKS, APPROVED);
        PartnerLink link = approvedLink();
        PartnerLinkApprovedForPartner event = approvedEvent(link);

        rabbitmqctl("stop_app");
        try {
            transition.record(link, approval(link), List.of(event));
            await().atMost(Duration.ofSeconds(30)).until(() -> failedPublicationsOf(event.messageId()) == 1);
        } finally {
            rabbitmqctl("start_app");
            rabbitmqctl("await_startup");
        }
        incompletePublications.resubmitIncompletePublications(ResubmissionOptions.defaults());

        queues.awaitFirst(webhooks, TestQueues.withMessageId(event.messageId()), Duration.ofSeconds(30));
        await().atMost(DELIVERY).until(() -> completedPublicationsOf(event.messageId()) == 1);
    }

    private PartnerLink approvedLink() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PartnerLink link = PartnerLink.restore(
                UUID.randomUUID(),
                "user-" + UUID.randomUUID(),
                partner.id(),
                "external-" + UUID.randomUUID(),
                "licensee-1",
                PartnerLinkStatus.PENDING,
                now.plusSeconds(3600),
                null,
                now,
                now);
        link.approve(now.plusSeconds(1));
        return link;
    }

    private static PartnerLinkHistoryEntry approval(PartnerLink link) {
        return PartnerLinkHistoryEntry.of(
                link, PartnerLinkStatus.PENDING, ActorType.USER, link.userId(), TransitionReason.USER_APPROVED);
    }

    private PartnerLinkApprovedForPartner approvedEvent(PartnerLink link) {
        return new PartnerLinkApprovedForPartner(
                UUID.randomUUID(),
                TRACE_ID,
                UUID.randomUUID(),
                partner.clientId(),
                UUID.randomUUID(),
                link.partnerExternalId(),
                link.id(),
                link.updatedAt());
    }

    private int publicationsOf(UUID messageId) {
        return countPublications("event_publication", messageId, "")
                + countPublications("event_publication_archive", messageId, "");
    }

    private int completedPublicationsOf(UUID messageId) {
        // completion-mode: archive moves a publication out of event_publication once it completes.
        return countPublications("event_publication_archive", messageId, " and completion_date is not null");
    }

    private int failedPublicationsOf(UUID messageId) {
        return countPublications("event_publication", messageId, " and status = 'FAILED'");
    }

    private int countPublications(String table, UUID messageId, String condition) {
        return jdbc.sql("select count(*) from " + table + " where serialized_event like :messageId" + condition)
                .param("messageId", "%" + messageId + "%")
                .query(Integer.class)
                .single();
    }

    private static void rabbitmqctl(String command) {
        Container.ExecResult result = NoxKeycloakContainer.retryingDockerCall(() -> {
            try {
                return InfrastructureContainers.RABBITMQ.execInContainer("rabbitmqctl", command);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        assertThat(result.getExitCode())
                .as("rabbitmqctl %s: %s", command, result.getStderr())
                .isZero();
    }
}
