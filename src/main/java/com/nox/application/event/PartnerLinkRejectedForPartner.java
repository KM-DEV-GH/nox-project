package com.nox.application.event;

import java.time.Instant;
import java.util.UUID;

/** The user rejected a link: webhook event for the partner, without {@code linkId} (spec FR-011). */
public record PartnerLinkRejectedForPartner(
        UUID messageId,
        String traceId,
        UUID correlationId,
        String partnerClientId,
        UUID requestId,
        String externalId,
        Instant occurredAt)
        implements PartnerLinkEvent {}
