package com.nox.domain.partnerlink;

/**
 * Result of acting on a {@link PartnerLink}. {@link #EXPIRED} means the action came at or after the
 * deadline and the link was moved to {@link PartnerLinkStatus#EXPIRED} instead; it is an outcome,
 * not an error, so the expiry is still written (research.md decision 5).
 */
public enum LinkDecisionOutcome {
    APPROVED,
    REJECTED,
    REVOKED,
    EXPIRED,
    NOT_ACTIONABLE
}
