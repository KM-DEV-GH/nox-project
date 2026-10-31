package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.ListMyPartnerLinksUseCase;
import com.nox.application.port.in.partnerlink.UserPartnerLink;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.domain.partner.Partner;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListMyPartnerLinksService implements ListMyPartnerLinksUseCase {

    private final PartnerLinkRepository links;
    private final PartnerRepository partners;
    private final Clock clock;

    public ListMyPartnerLinksService(PartnerLinkRepository links, PartnerRepository partners, Clock clock) {
        this.links = links;
        this.partners = partners;
        this.clock = clock;
    }

    @Override
    public List<UserPartnerLink> list(String userId) {
        Instant now = clock.instant();
        Map<UUID, Partner> partnersById = new HashMap<>();
        return links.findAllByUserId(userId).stream()
                .map(link -> UserLinks.view(
                        link,
                        partnersById.computeIfAbsent(
                                link.partnerId(), id -> partners.findById(id).orElseThrow()),
                        now))
                .toList();
    }
}
