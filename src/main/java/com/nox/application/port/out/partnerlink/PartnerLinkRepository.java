package com.nox.application.port.out.partnerlink;

import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PartnerLinkRepository {

    /**
     * Stores a new link or the change to a stored one, immediately.
     *
     * @throws ConcurrentPartnerLinkUpdateException when another transaction changed the link since it was read
     */
    void save(PartnerLink link);

    Optional<PartnerLink> findById(UUID id);

    /** The user's {@code PENDING} or {@code CONNECTED} link with the partner, if any. */
    Optional<PartnerLink> findActiveByUserAndPartner(String userId, UUID partnerId);

    /** The most recently created of the partner's links for {@code externalId} that has one of {@code statuses}. */
    Optional<PartnerLink> findLatestByPartnerAndExternalId(
            UUID partnerId, String externalId, Set<PartnerLinkStatus> statuses);

    List<PartnerLink> findAllByUserId(String userId);

    Optional<PartnerLink> findByIdAndUserId(UUID id, String userId);
}
