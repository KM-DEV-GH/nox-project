package com.nox.domain.payment;

import java.time.Instant;

/**
 * Payment-core's authoritative view of a payment after an initiation, confirmation or
 * cancellation (research.md D8).
 *
 * @param paymentCoreId payment-core's payment identifier
 * @param status payment-core's status mapped to NOX's, or {@code null} when payment-core reported one
 *     NOX does not recognise; an unrecognised status is never taken as success
 * @param money the money payment-core echoed
 * @param fee the fee payment-core charged
 * @param createdAt payment-core's creation time
 * @param expiresAt when the OTP stage ends, if reported
 * @param confirmedAt when the payment was confirmed, if reported
 */
public record ProcessorPayment(
        String paymentCoreId,
        PaymentStatus status,
        Money money,
        Money fee,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt) {}
