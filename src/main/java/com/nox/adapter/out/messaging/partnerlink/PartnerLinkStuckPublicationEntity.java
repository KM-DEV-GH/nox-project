package com.nox.adapter.out.messaging.partnerlink;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapping of {@code partner_link_stuck_publication}: an event publication flagged for alerting. */
@Entity
@Table(name = "partner_link_stuck_publication")
public class PartnerLinkStuckPublicationEntity {

    @Id
    private UUID id;

    @Column(name = "event_publication_id", nullable = false)
    private UUID eventPublicationId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "flagged_at", nullable = false)
    private Instant flaggedAt;

    protected PartnerLinkStuckPublicationEntity() {}

    PartnerLinkStuckPublicationEntity(UUID eventPublicationId, String eventType, Instant flaggedAt) {
        this.id = UUID.randomUUID();
        this.eventPublicationId = eventPublicationId;
        this.eventType = eventType;
        this.flaggedAt = flaggedAt;
    }

    UUID eventPublicationId() {
        return eventPublicationId;
    }

    String eventType() {
        return eventType;
    }

    Instant flaggedAt() {
        return flaggedAt;
    }
}
