package com.nox.application.port.in.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkRequestStatus;
import java.util.Optional;
import java.util.UUID;

/** The status a partner sees for one of its requests; identical for known and unknown emails (FR-012). */
public interface GetPartnerLinkRequestStatusUseCase {

    /** Empty when the request does not exist or belongs to another partner. */
    Optional<PartnerLinkRequestStatus> status(UUID partnerId, UUID requestId);
}
