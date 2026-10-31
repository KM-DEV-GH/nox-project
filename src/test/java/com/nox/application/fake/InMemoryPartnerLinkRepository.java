package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.ConcurrentPartnerLinkUpdateException;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stores copies and checks versions like the JPA adapter's optimistic lock. */
public class InMemoryPartnerLinkRepository implements PartnerLinkRepository {

    private final Map<UUID, PartnerLink> links = new LinkedHashMap<>();
    private final List<UUID> saves = new ArrayList<>();
    private int conflictsToRaise;
    private Runnable concurrentWrite;

    /** The next {@code count} saves fail as if another transaction had updated the link first. */
    public void failNextSavesWithConflict(int count) {
        conflictsToRaise = count;
    }

    /** Runs {@code write} at the start of the next save, as if another transaction had committed it first. */
    public void interleaveConcurrentWrite(Runnable write) {
        concurrentWrite = write;
    }

    public void put(PartnerLink link) {
        links.put(link.id(), copy(link, link.version() == null ? 0L : link.version()));
    }

    public List<UUID> saves() {
        return List.copyOf(saves);
    }

    public PartnerLink get(UUID id) {
        return links.get(id);
    }

    @Override
    public void save(PartnerLink link) {
        if (concurrentWrite != null) {
            Runnable write = concurrentWrite;
            concurrentWrite = null;
            write.run();
        }
        if (conflictsToRaise > 0) {
            conflictsToRaise--;
            throw new ConcurrentPartnerLinkUpdateException(link.id());
        }
        PartnerLink stored = links.get(link.id());
        if (stored != null && !stored.version().equals(link.version())) {
            throw new ConcurrentPartnerLinkUpdateException(link.id());
        }
        saves.add(link.id());
        links.put(link.id(), copy(link, stored == null ? 0L : stored.version() + 1));
    }

    @Override
    public Optional<PartnerLink> findById(UUID id) {
        return Optional.ofNullable(links.get(id)).map(link -> copy(link, link.version()));
    }

    @Override
    public Optional<PartnerLink> findActiveByUserAndPartner(String userId, UUID partnerId) {
        return links.values().stream()
                .filter(link -> link.userId().equals(userId) && link.partnerId().equals(partnerId))
                .filter(link ->
                        link.status() == PartnerLinkStatus.PENDING || link.status() == PartnerLinkStatus.CONNECTED)
                .findFirst()
                .map(link -> copy(link, link.version()));
    }

    @Override
    public Optional<PartnerLink> findLatestByPartnerAndExternalId(
            UUID partnerId, String externalId, Set<PartnerLinkStatus> statuses) {
        return links.values().stream()
                .filter(link -> link.partnerId().equals(partnerId)
                        && link.partnerExternalId().equals(externalId))
                .filter(link -> statuses.contains(link.status()))
                .max(Comparator.comparing(PartnerLink::createdAt))
                .map(link -> copy(link, link.version()));
    }

    @Override
    public List<PartnerLink> findAllByUserId(String userId) {
        return links.values().stream()
                .filter(link -> link.userId().equals(userId))
                .map(link -> copy(link, link.version()))
                .toList();
    }

    @Override
    public Optional<PartnerLink> findByIdAndUserId(UUID id, String userId) {
        return findById(id).filter(link -> link.userId().equals(userId));
    }

    private static PartnerLink copy(PartnerLink link, Long version) {
        return PartnerLink.restore(
                link.id(),
                link.userId(),
                link.partnerId(),
                link.partnerExternalId(),
                link.licenseeId(),
                link.status(),
                link.expiresAt(),
                version,
                link.createdAt(),
                link.updatedAt());
    }
}
