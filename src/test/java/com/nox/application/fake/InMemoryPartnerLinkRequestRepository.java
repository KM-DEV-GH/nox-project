package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPartnerLinkRequestRepository implements PartnerLinkRequestRepository {

    private final Map<UUID, PartnerLinkRequest> requests = new LinkedHashMap<>();

    public int size() {
        return requests.size();
    }

    public PartnerLinkRequest get(UUID id) {
        return requests.get(id);
    }

    @Override
    public void save(PartnerLinkRequest request) {
        requests.put(request.id(), copy(request));
    }

    @Override
    public Optional<PartnerLinkRequest> findById(UUID id) {
        return Optional.ofNullable(requests.get(id)).map(InMemoryPartnerLinkRequestRepository::copy);
    }

    @Override
    public Optional<PartnerLinkRequest> findByPartnerAndIdempotencyKey(UUID partnerId, String idempotencyKey) {
        return requests.values().stream()
                .filter(request -> request.partnerId().equals(partnerId)
                        && request.idempotencyKey().equals(idempotencyKey))
                .findFirst()
                .map(InMemoryPartnerLinkRequestRepository::copy);
    }

    @Override
    public Optional<PartnerLinkRequest> findOriginatingRequest(UUID linkId) {
        return requests.values().stream()
                .filter(request -> linkId.equals(request.linkId()))
                .min(Comparator.comparing(PartnerLinkRequest::submittedAt))
                .map(InMemoryPartnerLinkRequestRepository::copy);
    }

    private static PartnerLinkRequest copy(PartnerLinkRequest request) {
        return PartnerLinkRequest.restore(
                request.id(),
                request.partnerId(),
                request.idempotencyKey(),
                request.partnerExternalId(),
                request.licenseeId(),
                request.email(),
                request.lookupStatus(),
                request.linkId(),
                request.correlationId(),
                request.submittedAt(),
                request.expiresAt());
    }
}
