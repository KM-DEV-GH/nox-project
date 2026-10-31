package com.nox.application.service.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.event.PartnerLinkApprovalRequested;
import com.nox.application.fake.FixedTraceIdSource;
import com.nox.application.fake.InMemoryPartnerLinkHistoryRepository;
import com.nox.application.fake.InMemoryPartnerLinkRepository;
import com.nox.application.fake.InMemoryPartnerLinkRequestRepository;
import com.nox.application.fake.InMemoryPartnerRepository;
import com.nox.application.fake.RecordingEventPublisher;
import com.nox.application.fake.StubUserLookup;
import com.nox.application.port.out.partnerlink.UserLookupResult;
import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResolvePartnerLinkRequestServiceTest {

    private static final Instant SUBMITTED = Instant.parse("2026-10-26T09:00:00Z");
    private static final Instant NOW = SUBMITTED.plusSeconds(5);
    private static final Duration WINDOW = Duration.ofHours(72);
    private static final Partner PARTNER = new Partner(
            UUID.randomUUID(),
            "demo-partner",
            "Demo partner",
            null,
            PartnerStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    private static final UserLookupResult.Found ALICE = new UserLookupResult.Found("user-alice");

    private final InMemoryPartnerRepository partners = new InMemoryPartnerRepository();
    private final InMemoryPartnerLinkRequestRepository requests = new InMemoryPartnerLinkRequestRepository();
    private final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    private final InMemoryPartnerLinkHistoryRepository history = new InMemoryPartnerLinkHistoryRepository();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final StubUserLookup userLookup = new StubUserLookup();
    private final ResolvePartnerLinkRequestService service = new ResolvePartnerLinkRequestService(
            requests,
            links,
            partners,
            userLookup,
            new PartnerLinkTransition(links, history, events),
            new FixedTraceIdSource(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private PartnerLinkRequest request;

    @BeforeEach
    void submitRequest() {
        partners.put(PARTNER);
        request = PartnerLinkRequest.submit(
                UUID.randomUUID(),
                PARTNER.id(),
                "key-1",
                "external-1",
                "licensee-1",
                "alice@example.test",
                UUID.randomUUID(),
                SUBMITTED,
                WINDOW);
        requests.save(request);
    }

    @Test
    void createsAPendingLinkAndAsksTheUserToDecide() {
        userLookup.willReturn(ALICE);

        service.resolve(request.id());

        assertThat(userLookup.lookedUp()).containsExactly("alice@example.test");
        PartnerLinkRequest resolved = requests.get(request.id());
        assertThat(resolved.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(resolved.email()).isNull();
        PartnerLink link = links.get(resolved.linkId());
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(link.userId()).isEqualTo("user-alice");
        assertThat(link.partnerId()).isEqualTo(PARTNER.id());
        assertThat(link.partnerExternalId()).isEqualTo("external-1");
        assertThat(link.licenseeId()).isEqualTo("licensee-1");
        assertThat(link.expiresAt()).isEqualTo(request.expiresAt());
        assertThat(link.createdAt()).isEqualTo(NOW);
        assertThat(history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.linkId()).isEqualTo(link.id());
            assertThat(entry.fromStatus()).isNull();
            assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.PENDING);
            assertThat(entry.actorType()).isEqualTo(ActorType.PARTNER);
            assertThat(entry.actorReference()).isEqualTo(PARTNER.id().toString());
            assertThat(entry.reason()).isEqualTo(TransitionReason.LINK_CREATED);
        });
        assertThat(events.eventsOfType(PartnerLinkApprovalRequested.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.linkId()).isEqualTo(link.id());
                    assertThat(event.userId()).isEqualTo("user-alice");
                    assertThat(event.partnerName()).isEqualTo(PARTNER.name());
                    assertThat(event.licenseeId()).isEqualTo("licensee-1");
                    assertThat(event.expiresAt()).isEqualTo(request.expiresAt());
                    assertThat(event.correlationId()).isEqualTo(request.correlationId());
                    assertThat(event.traceId()).isEqualTo(FixedTraceIdSource.TRACE_ID);
                });
    }

    @Test
    void pointsAtAnExistingActiveLinkWithoutCreatingAnother() {
        PartnerLink existing = PartnerLink.restore(
                UUID.randomUUID(),
                "user-alice",
                PARTNER.id(),
                "external-0",
                "licensee-0",
                PartnerLinkStatus.CONNECTED,
                SUBMITTED,
                2L,
                SUBMITTED,
                SUBMITTED);
        links.put(existing);
        userLookup.willReturn(ALICE);

        service.resolve(request.id());

        PartnerLinkRequest resolved = requests.get(request.id());
        assertThat(resolved.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(resolved.linkId()).isEqualTo(existing.id());
        assertThat(resolved.email()).isNull();
        assertThat(links.saves()).isEmpty();
        assertThat(history.entries()).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void expiresAnOverduePendingLinkBeforeCreatingTheNewOne() {
        PartnerLink overdue = PartnerLink.restore(
                UUID.randomUUID(),
                "user-alice",
                PARTNER.id(),
                "external-0",
                "licensee-0",
                PartnerLinkStatus.PENDING,
                NOW.minusSeconds(1),
                0L,
                SUBMITTED.minus(WINDOW),
                SUBMITTED.minus(WINDOW));
        links.put(overdue);
        userLookup.willReturn(ALICE);

        service.resolve(request.id());

        assertThat(links.get(overdue.id()).status()).isEqualTo(PartnerLinkStatus.EXPIRED);
        UUID newLinkId = requests.get(request.id()).linkId();
        assertThat(newLinkId).isNotEqualTo(overdue.id());
        assertThat(links.get(newLinkId).status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(links.saves()).containsExactly(overdue.id(), newLinkId);
        assertThat(history.entries()).hasSize(2);
        assertThat(history.findByLinkId(overdue.id())).singleElement().satisfies(entry -> {
            assertThat(entry.fromStatus()).isEqualTo(PartnerLinkStatus.PENDING);
            assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.EXPIRED);
            assertThat(entry.actorType()).isEqualTo(ActorType.NOX);
            assertThat(entry.actorReference()).isNull();
            assertThat(entry.reason()).isEqualTo(TransitionReason.EXPIRED_ON_NEW_REQUEST);
            assertThat(entry.occurredAt()).isEqualTo(NOW);
        });
        assertThat(events.eventsOfType(PartnerLinkApprovalRequested.class))
                .singleElement()
                .extracting(PartnerLinkApprovalRequested::linkId)
                .isEqualTo(newLinkId);
    }

    @Test
    void recordsAnUnknownEmailAsNotFoundAndNothingElse() {
        userLookup.willReturn(new UserLookupResult.NotFound());

        service.resolve(request.id());

        PartnerLinkRequest resolved = requests.get(request.id());
        assertThat(resolved.lookupStatus()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(resolved.email()).isNull();
        assertThat(resolved.linkId()).isNull();
        assertThat(links.saves()).isEmpty();
        assertThat(history.entries()).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void recordsAFailedLookup() {
        userLookup.willReturn(new UserLookupResult.Failed());

        service.resolve(request.id());

        PartnerLinkRequest resolved = requests.get(request.id());
        assertThat(resolved.lookupStatus()).isEqualTo(LookupStatus.FAILED);
        assertThat(resolved.email()).isNull();
        assertThat(links.saves()).isEmpty();
        assertThat(events.events()).isEmpty();
    }

    @Test
    void ignoresARequestWhoseLookupHasFinished() {
        userLookup.willReturn(new UserLookupResult.NotFound());
        service.resolve(request.id());
        userLookup.willReturn(ALICE);

        service.resolve(request.id());

        assertThat(userLookup.lookedUp()).hasSize(1);
        assertThat(requests.get(request.id()).lookupStatus()).isEqualTo(LookupStatus.NOT_FOUND);
    }

    @Test
    void ignoresAnUnknownRequest() {
        service.resolve(UUID.randomUUID());

        assertThat(userLookup.lookedUp()).isEmpty();
    }
}
