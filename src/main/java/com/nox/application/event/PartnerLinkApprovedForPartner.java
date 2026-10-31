package com.nox.application.event;

import java.time.Instant;
import java.util.UUID;

/** The user approved a link: webhook event for the partner, the only one carrying {@code linkId} (spec FR-011). */
public record PartnerLinkApprovedForPartner(
        UUID messageId,
        String traceId,
        UUID correlationId,
        String partnerClientId,
        UUID requestId,
        String externalId,
        UUID linkId,
        Instant occurredAt)
        implements PartnerLinkEvent {}
