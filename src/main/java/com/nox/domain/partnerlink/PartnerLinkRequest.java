package com.nox.domain.partnerlink;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One link request submitted by a partner. Stored before the user lookup and kept even when no user
 * matches, so every request follows the same timeline (research.md decision 1). The email is kept
 * only until the lookup finishes.
 */
public final class PartnerLinkRequest {

    private final UUID id;
    private final UUID partnerId;
    private final String idempotencyKey;
    private final String partnerExternalId;
    private final String licenseeId;
    private final UUID correlationId;
    private final Instant submittedAt;
    private final Instant expiresAt;
    private String email;
    private LookupStatus lookupStatus;
    private UUID linkId;

    private PartnerLinkRequest(
            UUID id,
            UUID partnerId,
            String idempotencyKey,
            String partnerExternalId,
            String licenseeId,
            String email,
            LookupStatus lookupStatus,
            UUID linkId,
            UUID correlationId,
            Instant submittedAt,
            Instant expiresAt) {
        this.id = id;
        this.partnerId = partnerId;
        this.idempotencyKey = idempotencyKey;
        this.partnerExternalId = partnerExternalId;
        this.licenseeId = licenseeId;
        this.email = email;
        this.lookupStatus = lookupStatus;
        this.linkId = linkId;
        this.correlationId = correlationId;
        this.submittedAt = submittedAt;
        this.expiresAt = expiresAt;
    }

    /** A new request whose approval deadline is fixed now, at submission (spec FR-017). */
    public static PartnerLinkRequest submit(
            UUID id,
            UUID partnerId,
            String idempotencyKey,
            String partnerExternalId,
            String licenseeId,
            String email,
            UUID correlationId,
            Instant submittedAt,
            Duration approvalWindow) {
        return new PartnerLinkRequest(
                id,
                partnerId,
                idempotencyKey,
                partnerExternalId,
                licenseeId,
                email,
                LookupStatus.PENDING,
                null,
                correlationId,
                submittedAt,
                submittedAt.plus(approvalWindow));
    }

    public static PartnerLinkRequest restore(
            UUID id,
            UUID partnerId,
            String idempotencyKey,
            String partnerExternalId,
            String licenseeId,
            String email,
            LookupStatus lookupStatus,
            UUID linkId,
            UUID correlationId,
            Instant submittedAt,
            Instant expiresAt) {
        return new PartnerLinkRequest(
                id,
                partnerId,
                idempotencyKey,
                partnerExternalId,
                licenseeId,
                email,
                lookupStatus,
                linkId,
                correlationId,
                submittedAt,
                expiresAt);
    }

    /** The user was found: the request now follows {@code linkId} (a new link, or the existing active one). */
    public void resolve(UUID resolvedLinkId) {
        finishLookup(LookupStatus.RESOLVED);
        linkId = resolvedLinkId;
    }

    public void markNotFound() {
        finishLookup(LookupStatus.NOT_FOUND);
    }

    public void markFailed() {
        finishLookup(LookupStatus.FAILED);
    }

    /**
     * The status reported to the partner: {@code PROCESSING} until a decision or the deadline, then
     * the decision or {@code EXPIRED}.
     *
     * @param link the link this request resolved to, or {@code null} when there is none
     */
    public PartnerLinkRequestStatus reportedStatus(PartnerLink link, Instant now) {
        if (link == null) {
            return now.isBefore(expiresAt) ? PartnerLinkRequestStatus.PROCESSING : PartnerLinkRequestStatus.EXPIRED;
        }
        return switch (link.effectiveStatus(now)) {
            case PENDING -> PartnerLinkRequestStatus.PROCESSING;
            case CONNECTED -> PartnerLinkRequestStatus.CONNECTED;
            case REJECTED -> PartnerLinkRequestStatus.REJECTED;
            case EXPIRED -> PartnerLinkRequestStatus.EXPIRED;
            case REVOKED -> PartnerLinkRequestStatus.REVOKED;
            case SUSPENDED -> throw new IllegalStateException("SUSPENDED is reserved and never written");
        };
    }

    private void finishLookup(LookupStatus result) {
        lookupStatus = result;
        email = null;
    }

    public UUID id() {
        return id;
    }

    public UUID partnerId() {
        return partnerId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String partnerExternalId() {
        return partnerExternalId;
    }

    public String licenseeId() {
        return licenseeId;
    }

    /** The email to look up; {@code null} once the lookup has finished. */
    public String email() {
        return email;
    }

    public LookupStatus lookupStatus() {
        return lookupStatus;
    }

    /** The link this request resolved to; {@code null} until a user was found. */
    public UUID linkId() {
        return linkId;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
