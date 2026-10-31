package com.nox.domain.payment;

/**
 * The partner's currently active payment-core identity, managed internally (spec FR-005). Each
 * payment copies it at initiation, so a later change applies only to new payments (FR-007).
 *
 * @param providerUserId payment-core provider user
 * @param accountId the provider's active account
 */
public record PartnerPaymentAccount(String providerUserId, String accountId) {

    /** Never prints the identifiers, which must not reach logs. */
    @Override
    public String toString() {
        return "PartnerPaymentAccount[redacted]";
    }
}
