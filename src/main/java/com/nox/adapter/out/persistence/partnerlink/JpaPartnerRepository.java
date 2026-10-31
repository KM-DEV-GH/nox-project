package com.nox.adapter.out.persistence.partnerlink;

import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.domain.partner.Partner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPartnerRepository implements PartnerRepository {

    private final PartnerJpaRepository partners;

    public JpaPartnerRepository(PartnerJpaRepository partners) {
        this.partners = partners;
    }

    @Override
    public Optional<Partner> findById(UUID id) {
        return partners.findById(id).map(PartnerEntity::toDomain);
    }

    @Override
    public Optional<Partner> findByClientId(String clientId) {
        return partners.findByClientId(clientId).map(PartnerEntity::toDomain);
    }
}
