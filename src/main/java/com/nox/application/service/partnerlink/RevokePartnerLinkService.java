package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.LinkDecision;
import com.nox.application.port.in.partnerlink.RevokePartnerLinkUseCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RevokePartnerLinkService implements RevokePartnerLinkUseCase {

    private final PartnerLinkDecider decider;

    public RevokePartnerLinkService(PartnerLinkDecider decider) {
        this.decider = decider;
    }

    @Override
    public Optional<LinkDecision> revoke(UUID linkId, String userId) {
        return ConcurrentUpdateRetry.once(() -> decider.decide(linkId, userId, PartnerLinkDecider.UserAction.REVOKE));
    }
}
