package com.nox.domain.payment;

/**
 * Lifecycle of a {@link Payment} (data-model.md, Payment State Transitions). The {@code *_UNKNOWN}
 * states are not successes: payment-core may or may not have acted, so they wait for internal
 * reconciliation and are never retried automatically.
 */
public enum PaymentStatus {
    INITIATING,
    AWAITING_OTP,
    CONFIRMING,
    CANCELLING,
    CONFIRMED,
    CANCELLED,
    FAILED,
    EXPIRED,
    INITIATION_UNKNOWN,
    CONFIRMATION_UNKNOWN,
    CANCELLATION_UNKNOWN;

    /** A payment-core call is under way; no other action may start. */
    public boolean isInFlight() {
        return this == INITIATING || this == CONFIRMING || this == CANCELLING;
    }
}
