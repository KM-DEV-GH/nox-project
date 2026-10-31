package com.nox.adapter.out.messaging.partnerlink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;

/**
 * Keeps the transactional outbox moving (research.md decision 3a). Spring Modulith resubmits
 * incomplete publications only on restart, so this resubmits failed ones (a broker outage, a lookup
 * that lost a race) once they are older than the retry interval. A publication still incomplete
 * after the stuck age is recorded once in {@code partner_link_stuck_publication} and logged as an
 * error, for alerting. Run on a fixed delay by the scheduler.
 */
@Component
public class PartnerLinkStuckPublicationSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerLinkStuckPublicationSweeper.class);

    private final IncompleteEventPublications incomplete;
    private final EventPublicationRepository publications;
    private final PartnerLinkStuckPublicationJpaRepository stuck;
    private final Clock clock;
    private final Duration retryAfter;
    private final Duration stuckAge;

    public PartnerLinkStuckPublicationSweeper(
            IncompleteEventPublications incomplete,
            EventPublicationRepository publications,
            PartnerLinkStuckPublicationJpaRepository stuck,
            Clock clock,
            PartnerLinkPublicationSweepProperties properties) {
        this.incomplete = incomplete;
        this.publications = publications;
        this.stuck = stuck;
        this.clock = clock;
        this.retryAfter = properties.publicationSweepInterval();
        this.stuckAge = properties.stuckPublicationAge();
    }

    public void sweep() {
        incomplete.resubmitIncompletePublications(ResubmissionOptions.defaults().withMinAge(retryAfter));
        Instant now = clock.instant();
        publications.findIncompletePublicationsPublishedBefore(now.minus(stuckAge)).stream()
                .filter(publication -> !stuck.existsByEventPublicationId(publication.getIdentifier()))
                .forEach(publication -> flag(publication, now));
    }

    private void flag(TargetEventPublication publication, Instant now) {
        String eventType = publication.getEvent().getClass().getName();
        stuck.save(new PartnerLinkStuckPublicationEntity(publication.getIdentifier(), eventType, now));
        LOG.atError()
                .addKeyValue("eventPublicationId", publication.getIdentifier())
                .addKeyValue("eventType", eventType)
                .log("event publication stuck");
    }
}
