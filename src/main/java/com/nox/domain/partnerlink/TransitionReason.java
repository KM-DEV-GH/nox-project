package com.nox.domain.partnerlink;

import java.util.Locale;

/** Why a link transition was written; recorded in its history entry. */
public enum TransitionReason {
    LINK_CREATED,
    USER_APPROVED,
    USER_REJECTED,
    USER_REVOKED,
    EXPIRED_ON_LATE_ACTION,
    EXPIRED_ON_NEW_REQUEST;

    /** The stored and published form, e.g. {@code user_approved}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TransitionReason ofCode(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
