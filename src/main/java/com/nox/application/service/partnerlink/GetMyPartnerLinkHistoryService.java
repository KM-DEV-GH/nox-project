package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.GetMyPartnerLinkHistoryUseCase;
import com.nox.application.port.out.partnerlink.PartnerLinkHistoryRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetMyPartnerLinkHistoryService implements GetMyPartnerLinkHistoryUseCase {

    private final PartnerLinkRepository links;
    private final PartnerLinkHistoryRepository history;

    public GetMyPartnerLinkHistoryService(PartnerLinkRepository links, PartnerLinkHistoryRepository history) {
        this.links = links;
        this.history = history;
    }

    @Override
    public Optional<List<PartnerLinkHistoryEntry>> history(UUID linkId, String userId) {
        return links.findByIdAndUserId(linkId, userId).map(link -> history.findByLinkId(link.id()));
    }
}
