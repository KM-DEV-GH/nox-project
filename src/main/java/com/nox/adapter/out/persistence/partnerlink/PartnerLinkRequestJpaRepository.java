package com.nox.adapter.out.persistence.partnerlink;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerLinkRequestJpaRepository extends JpaRepository<PartnerLinkRequestEntity, UUID> {

    Optional<PartnerLinkRequestEntity> findByPartnerIdAndIdempotencyKey(UUID partnerId, String idempotencyKey);

    Optional<PartnerLinkRequestEntity> findFirstByLinkIdOrderBySubmittedAtAsc(UUID linkId);
}
