package com.nox.domain.partner;

/** Lifecycle of a partner integration. Only {@link #ACTIVE} partners may call. */
public enum PartnerStatus {
    ACTIVE,
    SUSPENDED,
    DEREGISTERED
}
