package com.nox.application.port.out.partnerlink;

import com.nox.domain.partner.Partner;
import java.util.Optional;
import java.util.UUID;

/** Reads partners; provisioning happens out of band. */
public interface PartnerRepository {

    Optional<Partner> findById(UUID id);

    Optional<Partner> findByClientId(String clientId);
}
