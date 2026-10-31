package com.nox.application.service.caller;

import com.nox.application.port.in.caller.FindCallablePartnerUseCase;
import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.domain.partner.Partner;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class FindCallablePartnerService implements FindCallablePartnerUseCase {

    private final PartnerRepository partners;
    private final Clock clock;

    public FindCallablePartnerService(PartnerRepository partners, Clock clock) {
        this.partners = partners;
        this.clock = clock;
    }

    @Override
    public Optional<Partner> findCallable(String clientId) {
        return partners.findByClientId(clientId).filter(partner -> partner.isCallableAt(clock.instant()));
    }
}
