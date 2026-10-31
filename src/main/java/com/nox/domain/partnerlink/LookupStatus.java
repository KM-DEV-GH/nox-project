package com.nox.domain.partnerlink;

/** Progress of the asynchronous user lookup for a {@link PartnerLinkRequest}. Internal only. */
public enum LookupStatus {
    PENDING,
    RESOLVED,
    NOT_FOUND,
    FAILED
}
