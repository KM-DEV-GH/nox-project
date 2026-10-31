package com.nox.application.service.partnerlink;

import com.nox.application.event.PartnerLinkRequestSubmitted;
import com.nox.application.port.in.partnerlink.SubmitPartnerLinkRequestUseCase;
import com.nox.application.port.out.partnerlink.DomainEventPublisherPort;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.application.port.out.partnerlink.TraceIdSource;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the request and, in the same transaction, the event that starts the user lookup after
 * commit (research.md decision 1). Every submission does exactly this, whatever the email.
 */
public class SubmitPartnerLinkRequestService implements SubmitPartnerLinkRequestUseCase {

    private final PartnerLinkRequestRepository requests;
    private final DomainEventPublisherPort events;
    private final TraceIdSource traces;
    private final Clock clock;
    private final Duration approvalWindow;

    public SubmitPartnerLinkRequestService(
            PartnerLinkRequestRepository requests,
            DomainEventPublisherPort events,
            TraceIdSource traces,
            Clock clock,
            Duration approvalWindow) {
        this.requests = requests;
        this.events = events;
        this.traces = traces;
        this.clock = clock;
        this.approvalWindow = approvalWindow;
    }

    @Override
    @Transactional
    public UUID submit(Command command) {
        return requests.findByPartnerAndIdempotencyKey(command.partnerId(), command.idempotencyKey())
                .map(PartnerLinkRequest::id)
                .orElseGet(() -> store(command));
    }

    private UUID store(Command command) {
        PartnerLinkRequest request = PartnerLinkRequest.submit(
                UUID.randomUUID(),
                command.partnerId(),
                command.idempotencyKey(),
                command.externalId(),
                command.licenseeId(),
                command.email(),
                UUID.randomUUID(),
                clock.instant(),
                approvalWindow);
        requests.save(request);
        events.publish(new PartnerLinkRequestSubmitted(
                UUID.randomUUID(), traces.currentTraceId(), request.correlationId(), request.id()));
        return request.id();
    }
}
