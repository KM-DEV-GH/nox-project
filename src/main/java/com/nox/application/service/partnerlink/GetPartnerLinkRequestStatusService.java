package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.GetPartnerLinkRequestStatusUseCase;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkRequestStatus;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetPartnerLinkRequestStatusService implements GetPartnerLinkRequestStatusUseCase {

    private final PartnerLinkRequestRepository requests;
    private final PartnerLinkRepository links;
    private final Clock clock;

    public GetPartnerLinkRequestStatusService(
            PartnerLinkRequestRepository requests, PartnerLinkRepository links, Clock clock) {
        this.requests = requests;
        this.links = links;
        this.clock = clock;
    }

    @Override
    public Optional<PartnerLinkRequestStatus> status(UUID partnerId, UUID requestId) {
        return requests.findById(requestId)
                .filter(request -> request.partnerId().equals(partnerId))
                .map(request -> request.reportedStatus(linkOf(request), clock.instant()));
    }

    private PartnerLink linkOf(PartnerLinkRequest request) {
        return request.linkId() == null
                ? null
                : links.findById(request.linkId()).orElseThrow();
    }
}
