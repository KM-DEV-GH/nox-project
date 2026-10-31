package com.nox.adapter.out.persistence.partnerlink;

import com.nox.application.port.out.partnerlink.ConcurrentPartnerLinkUpdateException;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPartnerLinkRepository implements PartnerLinkRepository {

    private static final Set<PartnerLinkStatus> ACTIVE =
            EnumSet.of(PartnerLinkStatus.PENDING, PartnerLinkStatus.CONNECTED);

    private final PartnerLinkJpaRepository links;

    public JpaPartnerLinkRepository(PartnerLinkJpaRepository links) {
        this.links = links;
    }

    /**
     * Flushes at once, so that a superseded link is written {@code EXPIRED} before its replacement is
     * inserted (the partial unique index sees them in that order) and a lost update surfaces here.
     */
    @Override
    public void save(PartnerLink link) {
        try {
            links.saveAndFlush(PartnerLinkEntity.of(link));
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentPartnerLinkUpdateException(link.id());
        }
    }

    @Override
    public Optional<PartnerLink> findById(UUID id) {
        return links.findById(id).map(PartnerLinkEntity::toDomain);
    }

    @Override
    public Optional<PartnerLink> findActiveByUserAndPartner(String userId, UUID partnerId) {
        return links.findFirstByUserIdAndPartnerIdAndStatusIn(userId, partnerId, ACTIVE)
                .map(PartnerLinkEntity::toDomain);
    }

    @Override
    public Optional<PartnerLink> findLatestByPartnerAndExternalId(
            UUID partnerId, String externalId, Set<PartnerLinkStatus> statuses) {
        return links.findFirstByPartnerIdAndPartnerExternalIdAndStatusInOrderByCreatedAtDesc(
                        partnerId, externalId, statuses)
                .map(PartnerLinkEntity::toDomain);
    }

    @Override
    public List<PartnerLink> findAllByUserId(String userId) {
        return links.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PartnerLinkEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<PartnerLink> findByIdAndUserId(UUID id, String userId) {
        return links.findByIdAndUserId(id, userId).map(PartnerLinkEntity::toDomain);
    }
}
