package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.application.port.out.partnerlink.ConcurrentPartnerLinkUpdateException;
import com.nox.application.port.out.partnerlink.PartnerLinkHistoryRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.PartnerFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The JPA adapters behind the persistence ports, against the Liquibase-built PostgreSQL schema. */
class PartnerLinkPersistenceIT extends KeycloakContainerSupport {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant DEADLINE = NOW.plus(Duration.ofHours(72));

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PartnerRepository partners;

    @Autowired
    private PartnerLinkRequestRepository requests;

    @Autowired
    private PartnerLinkRepository links;

    @Autowired
    private PartnerLinkHistoryRepository history;

    private Partner partner;

    @BeforeEach
    void createPartner() {
        partner = new PartnerFixtures(jdbc).active();
    }

    @Test
    void readsPartnersByIdAndClientId() {
        assertThat(partners.findById(partner.id())).contains(partner);
        assertThat(partners.findByClientId(partner.clientId())).contains(partner);
        assertThat(partners.findByClientId("no-such-partner")).isEmpty();
    }

    @Test
    void roundTripsARequestThroughItsLifecycle() {
        PartnerLinkRequest request = PartnerLinkRequest.submit(
                UUID.randomUUID(),
                partner.id(),
                "key-" + UUID.randomUUID(),
                "external-1",
                "licensee-1",
                "user@example.test",
                UUID.randomUUID(),
                NOW,
                Duration.ofHours(72));
        requests.save(request);

        assertThat(requests.findById(request.id()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(request);
        assertThat(requests.findByPartnerAndIdempotencyKey(partner.id(), request.idempotencyKey()))
                .get()
                .extracting(PartnerLinkRequest::id)
                .isEqualTo(request.id());

        PartnerLink link = newLink("user-" + UUID.randomUUID(), "external-1", NOW);
        links.save(link);
        request.resolve(link.id());
        requests.save(request);

        PartnerLinkRequest resolved = requests.findById(request.id()).orElseThrow();
        assertThat(resolved.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(resolved.linkId()).isEqualTo(link.id());
        assertThat(resolved.email()).isNull();
        assertThat(requests.findOriginatingRequest(link.id()))
                .get()
                .extracting(PartnerLinkRequest::id)
                .isEqualTo(request.id());
    }

    @Test
    void roundTripsALinkAndFindsItByEveryLookup() {
        String userId = "user-" + UUID.randomUUID();
        PartnerLink link = newLink(userId, "external-2", NOW);
        links.save(link);

        PartnerLink stored = links.findById(link.id()).orElseThrow();
        assertThat(stored).usingRecursiveComparison().ignoringFields("version").isEqualTo(link);
        assertThat(stored.version()).isZero();
        assertThat(links.findActiveByUserAndPartner(userId, partner.id())).isPresent();
        assertThat(links.findAllByUserId(userId)).extracting(PartnerLink::id).containsExactly(link.id());
        assertThat(links.findByIdAndUserId(link.id(), userId)).isPresent();
        assertThat(links.findByIdAndUserId(link.id(), "someone-else")).isEmpty();
    }

    @Test
    void appendsAndReadsHistoryInOrder() {
        PartnerLink link = newLink("user-" + UUID.randomUUID(), "external-3", NOW);
        links.save(link);
        PartnerLinkHistoryEntry created = PartnerLinkHistoryEntry.of(
                link, null, ActorType.PARTNER, partner.id().toString(), TransitionReason.LINK_CREATED);
        PartnerLink approved = links.findById(link.id()).orElseThrow();
        approved.approve(NOW.plusSeconds(5));
        links.save(approved);
        PartnerLinkHistoryEntry approval = PartnerLinkHistoryEntry.of(
                approved, PartnerLinkStatus.PENDING, ActorType.USER, approved.userId(), TransitionReason.USER_APPROVED);

        history.append(approval);
        history.append(created);

        assertThat(history.findByLinkId(link.id())).containsExactly(created, approval);
    }

    @Test
    void concurrentUpdateOfTheSameLinkFailsOneWriter() {
        PartnerLink link = newLink("user-" + UUID.randomUUID(), "external-4", NOW);
        links.save(link);
        PartnerLink first = links.findById(link.id()).orElseThrow();
        PartnerLink second = links.findById(link.id()).orElseThrow();

        first.approve(NOW.plusSeconds(1));
        second.reject(NOW.plusSeconds(1));
        links.save(first);

        assertThatThrownBy(() -> links.save(second)).isInstanceOf(ConcurrentPartnerLinkUpdateException.class);
        assertThat(links.findById(link.id()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.CONNECTED);
    }

    @Test
    void findsTheMostRecentlyCreatedLinkByExternalId() {
        String externalId = "external-" + UUID.randomUUID();
        PartnerLink older = established("user-" + UUID.randomUUID(), externalId, NOW.minusSeconds(60));
        PartnerLink newer = established("user-" + UUID.randomUUID(), externalId, NOW);
        PartnerLink pendingNewest = newLink("user-" + UUID.randomUUID(), externalId, NOW.plusSeconds(60));
        links.save(older);
        links.save(newer);
        links.save(pendingNewest);

        assertThat(links.findLatestByPartnerAndExternalId(
                        partner.id(), externalId, EnumSet.of(PartnerLinkStatus.CONNECTED, PartnerLinkStatus.REVOKED)))
                .get()
                .extracting(PartnerLink::id)
                .isEqualTo(newer.id());
        assertThat(links.findLatestByPartnerAndExternalId(
                        partner.id(), externalId, EnumSet.allOf(PartnerLinkStatus.class)))
                .get()
                .extracting(PartnerLink::id)
                .isEqualTo(pendingNewest.id());
    }

    private PartnerLink newLink(String userId, String externalId, Instant createdAt) {
        return PartnerLink.pending(
                UUID.randomUUID(), userId, partner.id(), externalId, "licensee-1", DEADLINE, createdAt);
    }

    private PartnerLink established(String userId, String externalId, Instant createdAt) {
        return PartnerLink.restore(
                UUID.randomUUID(),
                userId,
                partner.id(),
                externalId,
                "licensee-1",
                PartnerLinkStatus.CONNECTED,
                DEADLINE,
                null,
                createdAt,
                createdAt);
    }
}
