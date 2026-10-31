package com.nox.adapter.out.persistence.partnerlink;

import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPartnerLinkRequestRepository implements PartnerLinkRequestRepository {

    private final PartnerLinkRequestJpaRepository requests;

    public JpaPartnerLinkRequestRepository(PartnerLinkRequestJpaRepository requests) {
        this.requests = requests;
    }

    @Override
    public void save(PartnerLinkRequest request) {
        requests.saveAndFlush(PartnerLinkRequestEntity.of(request));
    }

    @Override
    public Optional<PartnerLinkRequest> findById(UUID id) {
        return requests.findById(id).map(PartnerLinkRequestEntity::toDomain);
    }

    @Override
    public Optional<PartnerLinkRequest> findByPartnerAndIdempotencyKey(UUID partnerId, String idempotencyKey) {
        return requests.findByPartnerIdAndIdempotencyKey(partnerId, idempotencyKey)
                .map(PartnerLinkRequestEntity::toDomain);
    }

    @Override
    public Optional<PartnerLinkRequest> findOriginatingRequest(UUID linkId) {
        return requests.findFirstByLinkIdOrderBySubmittedAtAsc(linkId).map(PartnerLinkRequestEntity::toDomain);
    }
}
