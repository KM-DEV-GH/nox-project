package com.nox.application.service.partnerlink;

import com.nox.application.fake.FixedTraceIdSource;
import com.nox.application.fake.InMemoryPartnerLinkHistoryRepository;
import com.nox.application.fake.InMemoryPartnerLinkRepository;
import com.nox.application.fake.InMemoryPartnerLinkRequestRepository;
import com.nox.application.fake.InMemoryPartnerRepository;
import com.nox.application.fake.RecordingEventPublisher;
import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** A partner, the request that created a link, and the link, behind in-memory ports. */
class UserDecisionFixture {

    static final Instant CREATED = Instant.parse("2026-10-26T09:00:00Z");
    static final Instant DEADLINE = CREATED.plus(Duration.ofHours(72));
    static final String USER_ID = "user-alice";

    final Partner partner = new Partner(
            UUID.randomUUID(),
            "demo-partner",
            "Demo partner",
            "https://demo.example.test",
            PartnerStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    final InMemoryPartnerRepository partners = new InMemoryPartnerRepository();
    final InMemoryPartnerLinkRequestRepository requests = new InMemoryPartnerLinkRequestRepository();
    final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    final InMemoryPartnerLinkHistoryRepository history = new InMemoryPartnerLinkHistoryRepository();
    final RecordingEventPublisher events = new RecordingEventPublisher();
    final PartnerLinkRequest request;

    UserDecisionFixture() {
        partners.put(partner);
        request = PartnerLinkRequest.submit(
                UUID.randomUUID(),
                partner.id(),
                "key-1",
                "external-1",
                "licensee-1",
                "alice@example.test",
                UUID.randomUUID(),
                CREATED,
                Duration.ofHours(72));
    }

    /** Stores a link of the given status, created by {@link #request}. */
    PartnerLink link(PartnerLinkStatus status) {
        PartnerLink link = PartnerLink.restore(
                UUID.randomUUID(),
                USER_ID,
                partner.id(),
                "external-1",
                "licensee-1",
                status,
                DEADLINE,
                0L,
                CREATED,
                CREATED);
        links.put(link);
        request.resolve(link.id());
        requests.save(request);
        return link;
    }

    PartnerLinkDecider deciderAt(Instant now) {
        return new PartnerLinkDecider(
                links,
                requests,
                partners,
                new PartnerLinkTransition(links, history, events),
                new FixedTraceIdSource(),
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
