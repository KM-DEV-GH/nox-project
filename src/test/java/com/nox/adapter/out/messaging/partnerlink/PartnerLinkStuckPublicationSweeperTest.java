package com.nox.adapter.out.messaging.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nox.application.event.PartnerLinkApprovedForPartner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.TargetEventPublication;

class PartnerLinkStuckPublicationSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    private static final Duration STUCK_AGE = Duration.ofMinutes(15);

    private final IncompleteEventPublications incomplete = mock(IncompleteEventPublications.class);
    private final EventPublicationRepository publications = mock(EventPublicationRepository.class);
    private final PartnerLinkStuckPublicationJpaRepository stuck = mock(PartnerLinkStuckPublicationJpaRepository.class);
    private final PartnerLinkStuckPublicationSweeper sweeper = new PartnerLinkStuckPublicationSweeper(
            incomplete,
            publications,
            stuck,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new PartnerLinkPublicationSweepProperties(RETRY_AFTER, STUCK_AGE));

    @Test
    void flagsAPublicationIncompleteForLongerThanTheConfiguredAge() {
        TargetEventPublication old = publication();
        when(publications.findIncompletePublicationsPublishedBefore(NOW.minus(STUCK_AGE)))
                .thenReturn(List.of(old));

        sweeper.sweep();

        ArgumentCaptor<PartnerLinkStuckPublicationEntity> flagged =
                ArgumentCaptor.forClass(PartnerLinkStuckPublicationEntity.class);
        verify(stuck).save(flagged.capture());
        assertThat(flagged.getValue().eventPublicationId()).isEqualTo(old.getIdentifier());
        assertThat(flagged.getValue().eventType()).isEqualTo(PartnerLinkApprovedForPartner.class.getName());
        assertThat(flagged.getValue().flaggedAt()).isEqualTo(NOW);
    }

    @Test
    void flagsEachPublicationOnlyOnce() {
        TargetEventPublication old = publication();
        when(publications.findIncompletePublicationsPublishedBefore(any())).thenReturn(List.of(old));
        when(stuck.existsByEventPublicationId(old.getIdentifier())).thenReturn(true);

        sweeper.sweep();

        verify(stuck, never()).save(any());
    }

    @Test
    void youngerOrCompletedPublicationsAreNotFlagged() {
        when(publications.findIncompletePublicationsPublishedBefore(NOW.minus(STUCK_AGE)))
                .thenReturn(List.of());

        sweeper.sweep();

        verify(stuck, never()).save(any());
    }

    @Test
    void resubmitsFailedPublicationsOlderThanTheRetryInterval() {
        when(publications.findIncompletePublicationsPublishedBefore(any())).thenReturn(List.of());

        sweeper.sweep();

        ArgumentCaptor<ResubmissionOptions> options = ArgumentCaptor.forClass(ResubmissionOptions.class);
        verify(incomplete).resubmitIncompletePublications(options.capture());
        assertThat(options.getValue().getMinAge()).isEqualTo(RETRY_AFTER);
    }

    private static TargetEventPublication publication() {
        TargetEventPublication publication = mock(TargetEventPublication.class);
        when(publication.getIdentifier()).thenReturn(UUID.randomUUID());
        when(publication.getEvent())
                .thenReturn(new PartnerLinkApprovedForPartner(
                        UUID.randomUUID(),
                        "trace",
                        UUID.randomUUID(),
                        "demo-partner",
                        UUID.randomUUID(),
                        "external-1",
                        UUID.randomUUID(),
                        NOW));
        return publication;
    }
}
