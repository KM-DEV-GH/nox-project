package com.nox.application.service.partnerlink;

import com.nox.application.event.PartnerLinkApprovalRequested;
import com.nox.application.port.in.partnerlink.ResolvePartnerLinkRequestUseCase;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.application.port.out.partnerlink.TraceIdSource;
import com.nox.application.port.out.partnerlink.UserLookupPort;
import com.nox.application.port.out.partnerlink.UserLookupResult;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.LinkDecisionOutcome;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a submitted request: a real user without an active link with the partner gets a
 * {@code PENDING} link and an approval request (FR-002, FR-003, FR-006). A user with an active link
 * keeps it; an unknown, ineligible or unreachable user only finishes the lookup. Requests whose
 * lookup already finished are ignored, so a redelivered event changes nothing.
 */
@Service
public class ResolvePartnerLinkRequestService implements ResolvePartnerLinkRequestUseCase {

    private final PartnerLinkRequestRepository requests;
    private final PartnerLinkRepository links;
    private final PartnerRepository partners;
    private final UserLookupPort userLookup;
    private final PartnerLinkTransition transition;
    private final TraceIdSource traces;
    private final Clock clock;

    public ResolvePartnerLinkRequestService(
            PartnerLinkRequestRepository requests,
            PartnerLinkRepository links,
            PartnerRepository partners,
            UserLookupPort userLookup,
            PartnerLinkTransition transition,
            TraceIdSource traces,
            Clock clock) {
        this.requests = requests;
        this.links = links;
        this.partners = partners;
        this.userLookup = userLookup;
        this.transition = transition;
        this.traces = traces;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void resolve(UUID requestId) {
        Optional<PartnerLinkRequest> pending =
                requests.findById(requestId).filter(request -> request.lookupStatus() == LookupStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        PartnerLinkRequest request = pending.get();
        switch (userLookup.lookUpByEmail(request.email())) {
            case UserLookupResult.Found user -> request.resolve(linkFor(request, user));
            case UserLookupResult.NotFound ignored -> request.markNotFound();
            case UserLookupResult.Failed ignored -> request.markFailed();
        }
        requests.save(request);
    }

    /** The user's active link with the partner, or a new {@code PENDING} one. */
    private UUID linkFor(PartnerLinkRequest request, UserLookupResult.Found user) {
        Instant now = clock.instant();
        Optional<PartnerLink> active = links.findActiveByUserAndPartner(user.userId(), request.partnerId());
        if (active.isPresent() && !expireIfOverdue(active.get(), now)) {
            return active.get().id();
        }
        return createLink(request, user, now);
    }

    private boolean expireIfOverdue(PartnerLink link, Instant now) {
        if (link.supersede(now) != LinkDecisionOutcome.EXPIRED) {
            return false;
        }
        transition.record(
                link,
                PartnerLinkHistoryEntry.of(
                        link, PartnerLinkStatus.PENDING, ActorType.NOX, null, TransitionReason.EXPIRED_ON_NEW_REQUEST),
                List.of());
        return true;
    }

    private UUID createLink(PartnerLinkRequest request, UserLookupResult.Found user, Instant now) {
        Partner partner = partners.findById(request.partnerId()).orElseThrow();
        PartnerLink link = PartnerLink.pending(
                UUID.randomUUID(),
                user.userId(),
                user.keycloakId(),
                partner.id(),
                request.partnerExternalId(),
                request.licenseeId(),
                request.expiresAt(),
                now);
        PartnerLinkHistoryEntry created = PartnerLinkHistoryEntry.of(
                link, null, ActorType.PARTNER, partner.id().toString(), TransitionReason.LINK_CREATED);
        PartnerLinkApprovalRequested approvalRequested = new PartnerLinkApprovalRequested(
                UUID.randomUUID(),
                traces.currentTraceId(),
                request.correlationId(),
                link.id(),
                link.userId(),
                partner.name(),
                link.licenseeId(),
                link.expiresAt());
        transition.record(link, created, List.of(approvalRequested));
        return link.id();
    }
}
