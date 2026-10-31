package com.nox.domain.partnerlink;

/**
 * Status of a {@link PartnerLink}. {@link #SUSPENDED} is reserved for a future feature and is never
 * written by this one (spec FR-018).
 */
public enum PartnerLinkStatus {
    PENDING,
    CONNECTED,
    REJECTED,
    EXPIRED,
    REVOKED,
    SUSPENDED
}
