package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.LinkDecision;
import com.nox.application.port.in.partnerlink.RejectPartnerLinkUseCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RejectPartnerLinkService implements RejectPartnerLinkUseCase {

    private final PartnerLinkDecider decider;

    public RejectPartnerLinkService(PartnerLinkDecider decider) {
        this.decider = decider;
    }

    @Override
    public Optional<LinkDecision> reject(UUID linkId, String userId) {
        return ConcurrentUpdateRetry.once(() -> decider.decide(linkId, userId, PartnerLinkDecider.UserAction.REJECT));
    }
}
