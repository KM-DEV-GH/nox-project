package com.nox.domain.payment;

import java.math.BigDecimal;

/**
 * Whether a partner may move one currency in one direction, and within which inclusive bounds
 * (data-model.md: Partner Payment Limit; spec FR-003).
 *
 * @param type direction the limit applies to
 * @param currencyCode the one currency the limit applies to
 * @param enabled only enabled limits allow initiation
 * @param minimumAmount inclusive lower bound, greater than zero
 * @param maximumAmount inclusive upper bound, not below the minimum
 */
public record PaymentLimit(
        PaymentType type, String currencyCode, boolean enabled, BigDecimal minimumAmount, BigDecimal maximumAmount) {

    public PaymentLimit {
        if (minimumAmount == null || minimumAmount.signum() <= 0) {
            throw new IllegalArgumentException("A limit needs a positive minimum");
        }
        if (maximumAmount == null || maximumAmount.compareTo(minimumAmount) < 0) {
            throw new IllegalArgumentException("A limit's maximum must not be below its minimum");
        }
    }

    public boolean allows(Money money) {
        return enabled
                && currencyCode.equals(money.currencyCode())
                && money.amount().compareTo(minimumAmount) >= 0
                && money.amount().compareTo(maximumAmount) <= 0;
    }
}
