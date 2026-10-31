package com.nox.application.port.in.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every written transition of one of the user's links, oldest first (FR-009, FR-010). */
public interface GetMyPartnerLinkHistoryUseCase {

    /** Empty for an unknown link or another user's. */
    Optional<List<PartnerLinkHistoryEntry>> history(UUID linkId, String userId);
}
