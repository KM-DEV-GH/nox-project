package com.nox.application.event;

import java.time.Instant;
import java.util.UUID;

/** A {@code PENDING} link was created; asks the user to decide (spec FR-006). */
public record PartnerLinkApprovalRequested(
        UUID messageId,
        String traceId,
        UUID correlationId,
        UUID linkId,
        String userId,
        String partnerName,
        String licenseeId,
        Instant expiresAt)
        implements PartnerLinkEvent {}
