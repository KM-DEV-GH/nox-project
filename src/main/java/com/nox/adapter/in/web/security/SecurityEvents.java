package com.nox.adapter.in.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security log events (FR-012). Only the message and the {@code reason} key-value are passed;
 * request, trace and caller context are added by the log formatter. Never logs credentials.
 */
final class SecurityEvents {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEvents.class);
    private static final String REASON = "reason";

    private SecurityEvents() {}

    static void authenticationFailed(FailureReason reason) {
        LOG.atWarn().addKeyValue(REASON, reason.code()).log("authentication failed");
    }

    static void identityProviderUnavailable() {
        LOG.atWarn().addKeyValue(REASON, FailureReason.JWKS_UNAVAILABLE.code()).log("identity provider unavailable");
    }

    static void accessDenied() {
        LOG.atWarn().addKeyValue(REASON, FailureReason.MISSING_ROLE.code()).log("access denied");
    }
}
