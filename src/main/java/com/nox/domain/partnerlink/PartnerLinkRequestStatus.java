package com.nox.domain.partnerlink;

/**
 * Status of a link request as reported to the partner (spec FR-012, FR-018). Derived, never stored,
 * so a known and an unknown email report the same sequence (data-model.md "Reported status").
 */
public enum PartnerLinkRequestStatus {
    PROCESSING,
    CONNECTED,
    REJECTED,
    EXPIRED,
    REVOKED
}
