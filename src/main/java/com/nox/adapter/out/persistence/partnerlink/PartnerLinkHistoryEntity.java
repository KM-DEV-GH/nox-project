package com.nox.adapter.out.persistence.partnerlink;

import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapping of the append-only {@code partner_link_history}. */
@Entity
@Table(name = "partner_link_history")
public class PartnerLinkHistoryEntity {

    @Id
    private UUID id;

    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PartnerLinkStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PartnerLinkStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "actor_reference")
    private String actorReference;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Convert(converter = TransitionReasonConverter.class)
    @Column(nullable = false)
    private TransitionReason reason;

    protected PartnerLinkHistoryEntity() {}

    static PartnerLinkHistoryEntity of(PartnerLinkHistoryEntry entry) {
        PartnerLinkHistoryEntity entity = new PartnerLinkHistoryEntity();
        entity.id = entry.id();
        entity.linkId = entry.linkId();
        entity.fromStatus = entry.fromStatus();
        entity.toStatus = entry.toStatus();
        entity.actorType = entry.actorType();
        entity.actorReference = entry.actorReference();
        entity.occurredAt = entry.occurredAt();
        entity.reason = entry.reason();
        return entity;
    }

    PartnerLinkHistoryEntry toDomain() {
        return new PartnerLinkHistoryEntry(
                id, linkId, fromStatus, toStatus, actorType, actorReference, occurredAt, reason);
    }
}
