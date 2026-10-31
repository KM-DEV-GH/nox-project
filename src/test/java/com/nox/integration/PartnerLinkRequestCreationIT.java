package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.OwlWireMockSupport;
import com.nox.integration.support.PartnerFixtures;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.TestQueues;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** US1, FR-001–FR-006, SC-002, SC-007: every submission gets the same answer; only a real user gets a link. */
class PartnerLinkRequestCreationIT extends KeycloakContainerSupport {

    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final Set<String> HOP_BY_HOP_HEADERS =
            Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OwlWireMockSupport owl = OwlWireMockSupport.forContainer();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PartnerLinkRequestRepository requests;

    @Autowired
    private PartnerLinkRepository links;

    private PartnerLinkApi api;
    private TestQueues queues;
    private Partner partner;

    @BeforeEach
    void setUp(@Autowired AmqpAdmin admin, @Autowired RabbitTemplate rabbit) {
        api = new PartnerLinkApi(client, TOKENS);
        queues = new TestQueues(admin, rabbit);
        partner = new PartnerFixtures(jdbc).active();
    }

    @Test
    void knownUserGetsAPendingLinkAndAnApprovalRequest() {
        String approvals = queues.bind(PartnerLinkMessages.EVENTS_EXCHANGE, PartnerLinkMessages.APPROVAL_REQUESTED);
        String email = OwlWireMockSupport.uniqueEmail();
        String userId = UUID.randomUUID().toString();
        owl.userFound(email, userId, UUID.randomUUID().toString());

        EntityExchangeResult<String> accepted =
                api.submit(partner, UUID.randomUUID().toString(), "external-1", email);

        assertAccepted(accepted);
        PartnerLinkRequest request = awaitLookup(PartnerLinkApi.requestIdOf(accepted));
        assertThat(request.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(request.email()).isNull();
        PartnerLink link = links.findById(request.linkId()).orElseThrow();
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(link.userId()).isEqualTo(userId);
        assertThat(link.expiresAt()).isEqualTo(request.expiresAt());

        Message message = queues.awaitFirst(approvals, mentioning(link.id()), ASYNC);
        assertThat(message.getMessageProperties().getCorrelationId())
                .isEqualTo(request.correlationId().toString());
        JsonNode data = jsonMapper
                .readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                .get("data");
        assertThat(data.propertyNames())
                .containsExactlyInAnyOrder("linkId", "userId", "partnerName", "licenseeId", "expiresAt");
        assertThat(data.get("userId").asString()).isEqualTo(userId);
        assertThat(data.get("partnerName").asString()).isEqualTo(partner.name());
        assertThat(data.get("licenseeId").asString()).isEqualTo("nox-gaming-eu");
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoLink() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.noUser(email);

        EntityExchangeResult<String> accepted =
                api.submit(partner, UUID.randomUUID().toString(), "external-2", email);

        assertAccepted(accepted);
        PartnerLinkRequest request = awaitLookup(PartnerLinkApi.requestIdOf(accepted));
        assertThat(request.lookupStatus()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(request.email()).isNull();
        assertThat(request.linkId()).isNull();
        assertThat(linkCountOf(partner)).isZero();
    }

    @Test
    void ineligibleOrAmbiguousMatchesLookExactlyLikeAnUnknownEmail() {
        String unverified = OwlWireMockSupport.uniqueEmail();
        String ambiguous = OwlWireMockSupport.uniqueEmail();
        owl.unverifiedUser(unverified);
        owl.ambiguousUser(ambiguous);

        EntityExchangeResult<String> first =
                api.submit(partner, UUID.randomUUID().toString(), "external-3", unverified);
        EntityExchangeResult<String> second =
                api.submit(partner, UUID.randomUUID().toString(), "external-4", ambiguous);

        assertSameShape(first, second);
        assertThat(awaitLookup(PartnerLinkApi.requestIdOf(first)).lookupStatus())
                .isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(awaitLookup(PartnerLinkApi.requestIdOf(second)).lookupStatus())
                .isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(linkCountOf(partner)).isZero();
    }

    @Test
    void aSecondRequestForAnActiveLinkPointsAtItWithoutAnotherLinkOrMessage() {
        String approvals = queues.bind(PartnerLinkMessages.EVENTS_EXCHANGE, PartnerLinkMessages.APPROVAL_REQUESTED);
        String email = OwlWireMockSupport.uniqueEmail();
        owl.userFound(email, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        EntityExchangeResult<String> first =
                api.submit(partner, UUID.randomUUID().toString(), "external-5", email);
        PartnerLinkRequest original = awaitLookup(PartnerLinkApi.requestIdOf(first));

        EntityExchangeResult<String> duplicate =
                api.submit(partner, UUID.randomUUID().toString(), "external-5", email);

        assertSameShape(first, duplicate);
        PartnerLinkRequest second = awaitLookup(PartnerLinkApi.requestIdOf(duplicate));
        assertThat(second.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(second.linkId()).isEqualTo(original.linkId());
        assertThat(linkCountOf(partner)).isEqualTo(1);
        List<Message> sent = queues.collect(approvals, mentioning(original.linkId()), Duration.ofSeconds(3));
        assertThat(sent).hasSize(1);
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheOriginalRequest() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.noUser(email);
        String key = UUID.randomUUID().toString();

        EntityExchangeResult<String> first = api.submit(partner, key, "external-6", email);
        EntityExchangeResult<String> replay = api.submit(partner, key, "external-6", email);

        assertSameShape(first, replay);
        assertThat(PartnerLinkApi.requestIdOf(replay)).isEqualTo(PartnerLinkApi.requestIdOf(first));
        assertThat(jdbc.sql("select count(*) from partner_link_request where partner_id = :partnerId")
                        .param("partnerId", partner.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(awaitLookup(PartnerLinkApi.requestIdOf(first)).lookupStatus())
                .isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(owl.lookupsFor(email)).isEqualTo(1);
    }

    @Test
    void knownAndUnknownEmailsGetIdenticalResponses() {
        String known = OwlWireMockSupport.uniqueEmail();
        String unknown = OwlWireMockSupport.uniqueEmail();
        owl.userFound(known, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        owl.noUser(unknown);

        EntityExchangeResult<String> forKnown =
                api.submit(partner, UUID.randomUUID().toString(), "external-7", known);
        EntityExchangeResult<String> forUnknown =
                api.submit(partner, UUID.randomUUID().toString(), "external-8", unknown);

        assertSameShape(forKnown, forUnknown);
    }

    private void assertAccepted(EntityExchangeResult<String> result) {
        assertThat(result.getStatus().value()).isEqualTo(202);
        JsonNode body = jsonMapper.readTree(result.getResponseBody());
        ContractAssertions.assertMatchesSchema(body, "LinkRequestAcceptedResponse");
        assertThat(body.get("data").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.get("meta").get("instance").asString()).isEqualTo(PartnerLinkApi.REQUESTS);
    }

    private void assertSameShape(EntityExchangeResult<String> first, EntityExchangeResult<String> second) {
        assertAccepted(first);
        assertAccepted(second);
        JsonNode one = jsonMapper.readTree(first.getResponseBody());
        JsonNode two = jsonMapper.readTree(second.getResponseBody());
        assertThat(two.propertyNames()).isEqualTo(one.propertyNames());
        assertThat(two.get("data").propertyNames()).isEqualTo(one.get("data").propertyNames());
        assertThat(two.get("meta").propertyNames()).isEqualTo(one.get("meta").propertyNames());
        assertThat(responseHeaderNames(second)).isEqualTo(responseHeaderNames(first));
    }

    /** Hop-by-hop headers such as {@code Connection} depend on the transport connection, not on the response. */
    private static Set<String> responseHeaderNames(EntityExchangeResult<String> result) {
        return result.getResponseHeaders().headerNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> !HOP_BY_HOP_HEADERS.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private PartnerLinkRequest awaitLookup(UUID requestId) {
        await().atMost(ASYNC)
                .until(() -> requests.findById(requestId)
                        .map(request -> request.lookupStatus() != LookupStatus.PENDING)
                        .orElse(false));
        return requests.findById(requestId).orElseThrow();
    }

    private int linkCountOf(Partner owner) {
        return jdbc.sql("select count(*) from partner_link where partner_id = :partnerId")
                .param("partnerId", owner.id())
                .query(Integer.class)
                .single();
    }

    private static Predicate<Message> mentioning(UUID linkId) {
        return message -> new String(message.getBody(), StandardCharsets.UTF_8).contains(linkId.toString());
    }
}
