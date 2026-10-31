package com.nox.application.service.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.fake.InMemoryPartnerLinkHistoryRepository;
import com.nox.application.fake.InMemoryPartnerLinkRepository;
import com.nox.application.fake.RecordingEventPublisher;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartnerLinkTransitionTest {

    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");

    private final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    private final InMemoryPartnerLinkHistoryRepository history = new InMemoryPartnerLinkHistoryRepository();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final PartnerLinkTransition transition = new PartnerLinkTransition(links, history, events);

    @Test
    void savesTheLinkAppendsOneEntryAndPublishesTheGivenEvents() {
        PartnerLink link = PartnerLink.pending(
                UUID.randomUUID(), "user-1", UUID.randomUUID(), "external-1", "licensee-1", NOW.plusSeconds(3600), NOW);
        PartnerLinkHistoryEntry entry =
                PartnerLinkHistoryEntry.of(link, null, ActorType.PARTNER, "partner-1", TransitionReason.LINK_CREATED);
        Object first = new Object();
        Object second = new Object();

        transition.record(link, entry, List.of(first, second));

        assertThat(links.saves()).containsExactly(link.id());
        assertThat(history.entries()).containsExactly(entry);
        assertThat(entry.actorType()).isEqualTo(ActorType.PARTNER);
        assertThat(entry.reason()).isEqualTo(TransitionReason.LINK_CREATED);
        assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(events.events()).containsExactly(first, second);
    }

    @Test
    void publishesNothingWhenNoEventsAreGiven() {
        PartnerLink link = PartnerLink.restore(
                UUID.randomUUID(),
                "user-1",
                UUID.randomUUID(),
                "external-1",
                "licensee-1",
                PartnerLinkStatus.CONNECTED,
                NOW.plusSeconds(3600),
                0L,
                NOW,
                NOW);
        links.put(link);
        link.revoke(NOW.plusSeconds(10));
        PartnerLinkHistoryEntry entry = PartnerLinkHistoryEntry.of(
                link, PartnerLinkStatus.CONNECTED, ActorType.USER, "user-1", TransitionReason.USER_REVOKED);

        transition.record(link, entry, List.of());

        assertThat(history.entries()).singleElement().satisfies(recorded -> {
            assertThat(recorded.fromStatus()).isEqualTo(PartnerLinkStatus.CONNECTED);
            assertThat(recorded.toStatus()).isEqualTo(PartnerLinkStatus.REVOKED);
            assertThat(recorded.occurredAt()).isEqualTo(NOW.plusSeconds(10));
        });
        assertThat(events.events()).isEmpty();
    }
}
