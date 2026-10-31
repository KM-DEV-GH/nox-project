package com.nox.application.port.in.partnerlink;

import com.nox.domain.partnerlink.PartnerLink;
import java.util.Optional;
import java.util.UUID;

/**
 * A partner's read of its established ({@code CONNECTED} or {@code REVOKED}) links. Every other link
 * is reported as absent, so a read never reveals that an email matched an account (research.md
 * decision 9).
 */
public interface GetPartnerLinkForPartnerUseCase {

    Optional<PartnerLink> byId(UUID partnerId, UUID linkId);

    /** The most recently created established link for the partner's own reference (FR-013). */
    Optional<PartnerLink> byExternalId(UUID partnerId, String externalId);
}
