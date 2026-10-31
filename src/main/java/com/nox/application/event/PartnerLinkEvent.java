package com.nox.application.event;

import java.util.UUID;

/** Metadata every partner link event carries onto its message (contracts/events.md). */
public interface PartnerLinkEvent {

    /** Unique per message and stable across redeliveries, so consumers can deduplicate. */
    UUID messageId();

    /** Trace ID of the request that caused the event. */
    String traceId();

    /** The {@code correlation_id} of the link request that started the link. */
    UUID correlationId();
}
