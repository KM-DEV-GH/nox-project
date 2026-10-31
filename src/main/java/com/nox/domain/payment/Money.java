package com.nox.domain.payment;

import java.math.BigDecimal;

/**
 * An amount in a currency. Amounts are compared by value, so {@code 100} and {@code 100.00} are the
 * same money; a fee may be zero, a payment amount may not (see {@link Payment}).
 *
 * @param amount non-negative decimal amount
 * @param currencyCode currency code as payment-core reports it
 */
public record Money(BigDecimal amount, String currencyCode) {

    public Money {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Money needs a non-negative amount");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("Money needs a currency code");
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), currencyCode);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** Same currency and the same amount by value, whatever the scale. */
    public boolean sameAs(Money other) {
        return currencyCode.equals(other.currencyCode()) && amount.compareTo(other.amount()) == 0;
    }
}
