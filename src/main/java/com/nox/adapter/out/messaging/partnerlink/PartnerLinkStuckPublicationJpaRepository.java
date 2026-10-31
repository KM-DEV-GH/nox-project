package com.nox.adapter.out.messaging.partnerlink;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerLinkStuckPublicationJpaRepository
        extends JpaRepository<PartnerLinkStuckPublicationEntity, UUID> {

    boolean existsByEventPublicationId(UUID eventPublicationId);
}
