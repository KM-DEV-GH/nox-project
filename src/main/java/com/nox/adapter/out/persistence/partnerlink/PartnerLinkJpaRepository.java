package com.nox.adapter.out.persistence.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerLinkJpaRepository extends JpaRepository<PartnerLinkEntity, UUID> {

    Optional<PartnerLinkEntity> findFirstByUserIdAndPartnerIdAndStatusIn(
            String userId, UUID partnerId, Collection<PartnerLinkStatus> statuses);

    Optional<PartnerLinkEntity> findFirstByPartnerIdAndPartnerExternalIdAndStatusInOrderByCreatedAtDesc(
            UUID partnerId, String partnerExternalId, Collection<PartnerLinkStatus> statuses);

    List<PartnerLinkEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PartnerLinkEntity> findByIdAndUserId(UUID id, String userId);
}
