package com.nox.application.port.out.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import java.util.List;
import java.util.UUID;

/** Append-only link history. */
public interface PartnerLinkHistoryRepository {

    void append(PartnerLinkHistoryEntry entry);

    /** The link's entries, oldest first. */
    List<PartnerLinkHistoryEntry> findByLinkId(UUID linkId);
}
