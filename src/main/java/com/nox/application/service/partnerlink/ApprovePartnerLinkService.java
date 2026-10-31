package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.ApprovePartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.LinkDecision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ApprovePartnerLinkService implements ApprovePartnerLinkUseCase {

    private final PartnerLinkDecider decider;

    public ApprovePartnerLinkService(PartnerLinkDecider decider) {
        this.decider = decider;
    }

    @Override
    public Optional<LinkDecision> approve(UUID linkId, String userId) {
        return ConcurrentUpdateRetry.once(() -> decider.decide(linkId, userId, PartnerLinkDecider.UserAction.APPROVE));
    }
}
