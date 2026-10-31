package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.PartnerLinkHistoryRepository;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class InMemoryPartnerLinkHistoryRepository implements PartnerLinkHistoryRepository {

    private final List<PartnerLinkHistoryEntry> entries = new ArrayList<>();

    public List<PartnerLinkHistoryEntry> entries() {
        return List.copyOf(entries);
    }

    @Override
    public void append(PartnerLinkHistoryEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<PartnerLinkHistoryEntry> findByLinkId(UUID linkId) {
        return entries.stream()
                .filter(entry -> entry.linkId().equals(linkId))
                .sorted(Comparator.comparing(PartnerLinkHistoryEntry::occurredAt))
                .toList();
    }
}
