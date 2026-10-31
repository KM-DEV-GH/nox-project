package com.nox.application.service.partnerlink;

import com.nox.application.event.PartnerLinkApprovedForPartner;
import com.nox.application.event.PartnerLinkApprovedForUser;
import com.nox.application.event.PartnerLinkRejectedForPartner;
import com.nox.application.event.PartnerLinkRejectedForUser;
import com.nox.application.port.in.partnerlink.LinkDecision;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.application.port.out.partnerlink.PartnerRepository;
import com.nox.application.port.out.partnerlink.TraceIdSource;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.LinkDecisionOutcome;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one user action to one of the user's links in a single transaction: the domain rule, the
 * history entry, and (for approve and reject) both messages. A late decision commits the expiry and
 * returns {@link LinkDecisionOutcome#EXPIRED} rather than throwing (research.md decision 5).
 */
@Component
class PartnerLinkDecider {

    /** The actions a user can take on their link. */
    public enum UserAction {
        APPROVE,
        REJECT,
        REVOKE
    }

    private final PartnerLinkRepository links;
    private final PartnerLinkRequestRepository requests;
    private final PartnerRepository partners;
    private final PartnerLinkTransition transition;
    private final TraceIdSource traces;
    private final Clock clock;

    public PartnerLinkDecider(
            PartnerLinkRepository links,
            PartnerLinkRequestRepository requests,
            PartnerRepository partners,
            PartnerLinkTransition transition,
            TraceIdSource traces,
            Clock clock) {
        this.links = links;
        this.requests = requests;
        this.partners = partners;
        this.transition = transition;
        this.traces = traces;
        this.clock = clock;
    }

    @Transactional
    public Optional<LinkDecision> decide(UUID linkId, String userId, UserAction action) {
        return links.findByIdAndUserId(linkId, userId).map(link -> decide(link, action, clock.instant()));
    }

    private LinkDecision decide(PartnerLink link, UserAction action, Instant now) {
        Partner partner = partners.findById(link.partnerId()).orElseThrow();
        PartnerLinkStatus from = link.status();
        LinkDecisionOutcome outcome = switch (action) {
            case APPROVE -> link.approve(now);
            case REJECT -> link.reject(now);
            case REVOKE -> link.revoke(now);
        };
        switch (outcome) {
            case APPROVED -> record(link, from, TransitionReason.USER_APPROVED, approvedEvents(link, partner));
            case REJECTED -> record(link, from, TransitionReason.USER_REJECTED, rejectedEvents(link, partner));
            case REVOKED -> record(link, from, TransitionReason.USER_REVOKED, List.of());
            case EXPIRED ->
                transition.record(
                        link,
                        PartnerLinkHistoryEntry.of(
                                link, from, ActorType.NOX, null, TransitionReason.EXPIRED_ON_LATE_ACTION),
                        List.of());
            case NOT_ACTIONABLE -> {}
        }
        return new LinkDecision(outcome, UserLinks.view(link, partner, now));
    }

    private void record(PartnerLink link, PartnerLinkStatus from, TransitionReason reason, List<?> events) {
        transition.record(link, PartnerLinkHistoryEntry.of(link, from, ActorType.USER, link.userId(), reason), events);
    }

    private List<Object> approvedEvents(PartnerLink link, Partner partner) {
        PartnerLinkRequest origin = originOf(link);
        String traceId = traces.currentTraceId();
        return List.of(
                new PartnerLinkApprovedForUser(
                        UUID.randomUUID(),
                        traceId,
                        origin.correlationId(),
                        link.id(),
                        link.userId(),
                        partner.name(),
                        link.updatedAt()),
                new PartnerLinkApprovedForPartner(
                        UUID.randomUUID(),
                        traceId,
                        origin.correlationId(),
                        partner.clientId(),
                        origin.id(),
                        link.partnerExternalId(),
                        link.id(),
                        link.updatedAt()));
    }

    private List<Object> rejectedEvents(PartnerLink link, Partner partner) {
        PartnerLinkRequest origin = originOf(link);
        String traceId = traces.currentTraceId();
        return List.of(
                new PartnerLinkRejectedForUser(
                        UUID.randomUUID(),
                        traceId,
                        origin.correlationId(),
                        link.id(),
                        link.userId(),
                        partner.name(),
                        link.updatedAt()),
                new PartnerLinkRejectedForPartner(
                        UUID.randomUUID(),
                        traceId,
                        origin.correlationId(),
                        partner.clientId(),
                        origin.id(),
                        link.partnerExternalId(),
                        link.updatedAt()));
    }

    /** Every link is created by a request; its webhook events name that request. */
    private PartnerLinkRequest originOf(PartnerLink link) {
        return requests.findOriginatingRequest(link.id())
                .orElseThrow(() -> new IllegalStateException("Link " + link.id() + " has no originating request"));
    }
}
