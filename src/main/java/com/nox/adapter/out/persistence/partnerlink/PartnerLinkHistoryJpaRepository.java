package com.nox.adapter.out.persistence.partnerlink;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerLinkHistoryJpaRepository extends JpaRepository<PartnerLinkHistoryEntity, UUID> {

    List<PartnerLinkHistoryEntity> findAllByLinkIdOrderByOccurredAtAsc(UUID linkId);
}
