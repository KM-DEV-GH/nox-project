package com.nox.application.port.in.payment;

/** Why a payment operation did not succeed; each maps to one documented problem type. */
public enum PaymentFailure {
    /** The link or payment does not exist for this partner, or was never established. */
    LINK_NOT_FOUND,
    /** The link was established but is no longer connected. */
    LINK_NOT_CONNECTED,
    PAYMENT_NOT_FOUND,
    /** The currency is not enabled for the partner and type, or the amount is outside its bounds. */
    LIMIT_EXCEEDED,
    /** The partner has no active account, or the type has no configured method. */
    NOT_CONFIGURED,
    /** The idempotency key was used with different payment details. */
    IDEMPOTENCY_CONFLICT,
    /** The partner already has a payment with this external ID, under another idempotency key. */
    EXTERNAL_ID_CONFLICT,
    /** Another request is processing the same payment. */
    IN_PROGRESS,
    /** The payment is not in a state that allows the action. */
    NOT_ACTIONABLE,
    /** Payment-core refused to create the payment. */
    REJECTED,
    /** Payment-core refused the OTP. */
    OTP_REJECTED,
    /** Payment-core was not called because it is currently considered unavailable. */
    PROCESSOR_UNAVAILABLE,
    /** Payment-core gave no authoritative answer; the payment awaits reconciliation. */
    UNRESOLVED
}
