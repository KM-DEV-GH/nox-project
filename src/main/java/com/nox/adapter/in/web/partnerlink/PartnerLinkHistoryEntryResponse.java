package com.nox.adapter.in.web.partnerlink;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.time.Instant;

/** Contract {@code PartnerLinkHistoryEntryData}; {@code fromStatus} is an explicit {@code null} on creation. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PartnerLinkHistoryEntryResponse(
        PartnerLinkStatus fromStatus,
        PartnerLinkStatus toStatus,
        ActorType actorType,
        Instant occurredAt,
        String reason) {

    static PartnerLinkHistoryEntryResponse of(PartnerLinkHistoryEntry entry) {
        return new PartnerLinkHistoryEntryResponse(
                entry.fromStatus(),
                entry.toStatus(),
                entry.actorType(),
                entry.occurredAt(),
                entry.reason().code());
    }
}
