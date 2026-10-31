package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.GetPartnerLinkForPartnerUseCase;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetPartnerLinkForPartnerService implements GetPartnerLinkForPartnerUseCase {

    private static final Set<PartnerLinkStatus> ESTABLISHED =
            EnumSet.of(PartnerLinkStatus.CONNECTED, PartnerLinkStatus.REVOKED);

    private final PartnerLinkRepository links;

    public GetPartnerLinkForPartnerService(PartnerLinkRepository links) {
        this.links = links;
    }

    @Override
    public Optional<PartnerLink> byId(UUID partnerId, UUID linkId) {
        return links.findById(linkId)
                .filter(link -> link.partnerId().equals(partnerId))
                .filter(PartnerLink::isEstablished);
    }

    @Override
    public Optional<PartnerLink> byExternalId(UUID partnerId, String externalId) {
        return links.findLatestByPartnerAndExternalId(partnerId, externalId, ESTABLISHED);
    }
}
