package com.nox.adapter.out.persistence.partnerlink;

import com.nox.application.port.out.partnerlink.PartnerLinkHistoryRepository;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPartnerLinkHistoryRepository implements PartnerLinkHistoryRepository {

    private final PartnerLinkHistoryJpaRepository history;

    public JpaPartnerLinkHistoryRepository(PartnerLinkHistoryJpaRepository history) {
        this.history = history;
    }

    @Override
    public void append(PartnerLinkHistoryEntry entry) {
        history.save(PartnerLinkHistoryEntity.of(entry));
    }

    @Override
    public List<PartnerLinkHistoryEntry> findByLinkId(UUID linkId) {
        return history.findAllByLinkIdOrderByOccurredAtAsc(linkId).stream()
                .map(PartnerLinkHistoryEntity::toDomain)
                .toList();
    }
}
