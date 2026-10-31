package com.nox.adapter.out.persistence.partnerlink;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerJpaRepository extends JpaRepository<PartnerEntity, UUID> {

    Optional<PartnerEntity> findByClientId(String clientId);
}
