package com.nox.application.event;

import java.time.Instant;
import java.util.UUID;

/** The user approved a link: notification for the user (spec FR-011). */
public record PartnerLinkApprovedForUser(
        UUID messageId,
        String traceId,
        UUID correlationId,
        UUID linkId,
        String userId,
        String partnerName,
        Instant occurredAt)
        implements PartnerLinkEvent {}
