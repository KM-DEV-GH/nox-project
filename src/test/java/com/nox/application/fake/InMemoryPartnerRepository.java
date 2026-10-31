package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.domain.partner.Partner;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPartnerRepository implements PartnerRepository {

    private final Map<UUID, Partner> partners = new LinkedHashMap<>();

    public void put(Partner partner) {
        partners.put(partner.id(), partner);
    }

    @Override
    public Optional<Partner> findById(UUID id) {
        return Optional.ofNullable(partners.get(id));
    }

    @Override
    public Optional<Partner> findByClientId(String clientId) {
        return partners.values().stream()
                .filter(partner -> partner.clientId().equals(clientId))
                .findFirst();
    }
}
