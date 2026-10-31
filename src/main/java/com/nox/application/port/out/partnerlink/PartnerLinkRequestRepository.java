package com.nox.application.port.out.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkRequest;
import java.util.Optional;
import java.util.UUID;

public interface PartnerLinkRequestRepository {

    void save(PartnerLinkRequest request);

    Optional<PartnerLinkRequest> findById(UUID id);

    Optional<PartnerLinkRequest> findByPartnerAndIdempotencyKey(UUID partnerId, String idempotencyKey);

    /** The earliest request that resolved to {@code linkId}: the one that created the link. */
    Optional<PartnerLinkRequest> findOriginatingRequest(UUID linkId);
}
