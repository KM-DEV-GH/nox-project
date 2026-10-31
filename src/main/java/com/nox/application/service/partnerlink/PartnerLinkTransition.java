package com.nox.application.service.partnerlink;

import com.nox.application.port.out.partnerlink.DomainEventPublisherPort;
import com.nox.application.port.out.partnerlink.PartnerLinkHistoryRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one link transition atomically: the link, its history entry, and the events that announce
 * it (spec FR-009; research.md decision 3a). Joins the caller's transaction when there is one.
 */
@Component
class PartnerLinkTransition {

    private final PartnerLinkRepository links;
    private final PartnerLinkHistoryRepository history;
    private final DomainEventPublisherPort events;

    public PartnerLinkTransition(
            PartnerLinkRepository links, PartnerLinkHistoryRepository history, DomainEventPublisherPort events) {
        this.links = links;
        this.history = history;
        this.events = events;
    }

    @Transactional
    public void record(PartnerLink link, PartnerLinkHistoryEntry entry, List<?> announcements) {
        links.save(link);
        history.append(entry);
        announcements.forEach(events::publish);
    }
}
