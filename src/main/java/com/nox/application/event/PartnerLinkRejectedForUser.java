package com.nox.application.event;

import java.time.Instant;
import java.util.UUID;

/** The user rejected a link: notification for the user (spec FR-011). */
public record PartnerLinkRejectedForUser(
        UUID messageId,
        String traceId,
        UUID correlationId,
        UUID linkId,
        String userId,
        String partnerName,
        Instant occurredAt)
        implements PartnerLinkEvent {}
