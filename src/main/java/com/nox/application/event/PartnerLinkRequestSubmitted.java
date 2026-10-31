package com.nox.application.event;

import java.util.UUID;

/** A link request was stored; starts the asynchronous user lookup. Internal, never externalized. */
public record PartnerLinkRequestSubmitted(UUID messageId, String traceId, UUID correlationId, UUID requestId)
        implements PartnerLinkEvent {}
