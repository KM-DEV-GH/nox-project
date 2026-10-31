package com.nox.domain.partnerlink;

import java.time.Instant;
import java.util.UUID;

/**
 * One written transition of a {@link PartnerLink} (spec FR-009, FR-010). Append-only.
 *
 * @param id identity
 * @param linkId the link that changed
 * @param fromStatus previous status; {@code null} for the creation entry
 * @param toStatus new status
 * @param actorType who caused the transition
 * @param actorReference Owl {@code user_id} for a user, partner id for a partner, {@code null} for NOX
 * @param occurredAt when the transition happened
 * @param reason why it happened
 */
public record PartnerLinkHistoryEntry(
        UUID id,
        UUID linkId,
        PartnerLinkStatus fromStatus,
        PartnerLinkStatus toStatus,
        ActorType actorType,
        String actorReference,
        Instant occurredAt,
        TransitionReason reason) {

    /** The entry for the transition {@code link} has just made from {@code fromStatus}, timed at its last update. */
    public static PartnerLinkHistoryEntry of(
            PartnerLink link,
            PartnerLinkStatus fromStatus,
            ActorType actorType,
            String actorReference,
            TransitionReason reason) {
        return new PartnerLinkHistoryEntry(
                UUID.randomUUID(),
                link.id(),
                fromStatus,
                link.status(),
                actorType,
                actorReference,
                link.updatedAt(),
                reason);
    }
}
