package com.nox.adapter.in.event;

import com.nox.adapter.tracing.TraceContinuation;
import com.nox.application.event.PartnerLinkRequestSubmitted;
import com.nox.application.port.in.partnerlink.ResolvePartnerLinkRequestUseCase;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Starts the user lookup after the request is committed, asynchronously and in its own transaction
 * (research.md decision 1). The event publication registry keeps the event until this completes, so
 * a crash does not lose the lookup; a failure is retried by resubmission.
 */
@Component
public class PartnerLinkRequestSubmittedListener {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerLinkRequestSubmittedListener.class);

    private final ResolvePartnerLinkRequestUseCase resolve;
    private final TraceContinuation traces;

    public PartnerLinkRequestSubmittedListener(ResolvePartnerLinkRequestUseCase resolve, TraceContinuation traces) {
        this.resolve = resolve;
        this.traces = traces;
    }

    @ApplicationModuleListener
    public void on(PartnerLinkRequestSubmitted event) {
        Map<String, String> context = Map.of(
                TraceContinuation.MESSAGING_OPERATION, "process",
                TraceContinuation.MESSAGING_MESSAGE_ID, event.messageId().toString(),
                TraceContinuation.CORRELATION_ID, event.correlationId().toString());
        traces.run(event.traceId(), "partner-link lookup", context, () -> {
            LOG.info("resolving link request");
            resolve.resolve(event.requestId());
        });
    }
}
