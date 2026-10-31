package com.nox.domain.partnerlink;

import java.time.Instant;
import java.util.UUID;

/**
 * The connection between one user and one partner, and its rules (data-model.md, state
 * transitions). Only the user approves, rejects or revokes; only NOX writes {@code EXPIRED}. Every
 * action takes the current time, so the deadline check needs no scheduler (research.md decision 5).
 */
public final class PartnerLink {

    private final UUID id;
    private final String userId;
    private final UUID partnerId;
    private final String partnerExternalId;
    private final String licenseeId;
    private final Instant expiresAt;
    private final Long version;
    private final Instant createdAt;
    private PartnerLinkStatus status;
    private Instant updatedAt;

    private PartnerLink(
            UUID id,
            String userId,
            UUID partnerId,
            String partnerExternalId,
            String licenseeId,
            PartnerLinkStatus status,
            Instant expiresAt,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.partnerId = partnerId;
        this.partnerExternalId = partnerExternalId;
        this.licenseeId = licenseeId;
        this.status = status;
        this.expiresAt = expiresAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new link awaiting the user's decision until {@code expiresAt}; not yet stored, so it has no version. */
    public static PartnerLink pending(
            UUID id,
            String userId,
            UUID partnerId,
            String partnerExternalId,
            String licenseeId,
            Instant expiresAt,
            Instant now) {
        return new PartnerLink(
                id,
                userId,
                partnerId,
                partnerExternalId,
                licenseeId,
                PartnerLinkStatus.PENDING,
                expiresAt,
                null,
                now,
                now);
    }

    /** A link as stored; {@code version} is {@code null} only for a link that was never stored. */
    public static PartnerLink restore(
            UUID id,
            String userId,
            UUID partnerId,
            String partnerExternalId,
            String licenseeId,
            PartnerLinkStatus status,
            Instant expiresAt,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        return new PartnerLink(
                id, userId, partnerId, partnerExternalId, licenseeId, status, expiresAt, version, createdAt, updatedAt);
    }

    /** The user approves: {@code CONNECTED} before the deadline, otherwise {@code EXPIRED}. */
    public LinkDecisionOutcome approve(Instant now) {
        return decide(now, PartnerLinkStatus.CONNECTED, LinkDecisionOutcome.APPROVED);
    }

    /** The user rejects: {@code REJECTED} before the deadline, otherwise {@code EXPIRED}. */
    public LinkDecisionOutcome reject(Instant now) {
        return decide(now, PartnerLinkStatus.REJECTED, LinkDecisionOutcome.REJECTED);
    }

    /** The user revokes an established connection; nothing else can be revoked (spec FR-016). */
    public LinkDecisionOutcome revoke(Instant now) {
        if (status != PartnerLinkStatus.CONNECTED) {
            return LinkDecisionOutcome.NOT_ACTIONABLE;
        }
        moveTo(PartnerLinkStatus.REVOKED, now);
        return LinkDecisionOutcome.REVOKED;
    }

    /**
     * A new request for the same user and partner arrived: an undecided link past its deadline is
     * written {@code EXPIRED} so it no longer counts as active (spec FR-017).
     */
    public LinkDecisionOutcome supersede(Instant now) {
        if (!isOverdue(now)) {
            return LinkDecisionOutcome.NOT_ACTIONABLE;
        }
        moveTo(PartnerLinkStatus.EXPIRED, now);
        return LinkDecisionOutcome.EXPIRED;
    }

    /** The status to report: an undecided link at or after its deadline reads as {@code EXPIRED}, without a write. */
    public PartnerLinkStatus effectiveStatus(Instant now) {
        return isOverdue(now) ? PartnerLinkStatus.EXPIRED : status;
    }

    /** {@code true} for an undecided link at or after its deadline. */
    public boolean isOverdue(Instant now) {
        return status == PartnerLinkStatus.PENDING && !now.isBefore(expiresAt);
    }

    /** {@code true} once the user has connected, including after a revocation; partners may read only these. */
    public boolean isEstablished() {
        return status == PartnerLinkStatus.CONNECTED || status == PartnerLinkStatus.REVOKED;
    }

    private LinkDecisionOutcome decide(Instant now, PartnerLinkStatus decided, LinkDecisionOutcome outcome) {
        if (status != PartnerLinkStatus.PENDING) {
            return LinkDecisionOutcome.NOT_ACTIONABLE;
        }
        if (isOverdue(now)) {
            moveTo(PartnerLinkStatus.EXPIRED, now);
            return LinkDecisionOutcome.EXPIRED;
        }
        moveTo(decided, now);
        return outcome;
    }

    private void moveTo(PartnerLinkStatus next, Instant now) {
        status = next;
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    /** Owl {@code user_id}: the canonical user identity, used for uniqueness. */
    public String userId() {
        return userId;
    }

    public UUID partnerId() {
        return partnerId;
    }

    public String partnerExternalId() {
        return partnerExternalId;
    }

    public String licenseeId() {
        return licenseeId;
    }

    public PartnerLinkStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
