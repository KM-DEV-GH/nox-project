package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.GetMyPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.UserPartnerLink;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetMyPartnerLinkService implements GetMyPartnerLinkUseCase {

    private final PartnerLinkRepository links;
    private final PartnerRepository partners;
    private final Clock clock;

    public GetMyPartnerLinkService(PartnerLinkRepository links, PartnerRepository partners, Clock clock) {
        this.links = links;
        this.partners = partners;
        this.clock = clock;
    }

    @Override
    public Optional<UserPartnerLink> get(UUID linkId, String userId) {
        return links.findByIdAndUserId(linkId, userId)
                .map(link ->
                        UserLinks.view(link, partners.findById(link.partnerId()).orElseThrow(), clock.instant()));
    }
}
