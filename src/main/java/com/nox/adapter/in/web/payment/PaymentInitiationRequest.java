package com.nox.adapter.in.web.payment;

import com.nox.domain.payment.Money;
import com.nox.domain.payment.PaymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Contract {@code PaymentInitiation}. Unknown properties are rejected ({@code additionalProperties:
 * false}), so a partner cannot supply an account, user, partner or payment method.
 */
public record PaymentInitiationRequest(
        @NotNull PaymentType type,
        @NotNull @Valid MoneyRequest money,
        @NotBlank @Size(max = 128) String externalId,
        @NotBlank @Size(max = 128) String transactionReference,
        @Size(max = 512) String description) {

    /** Contract {@code Money}, with a strictly positive amount. */
    public record MoneyRequest(
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 3, max = 12) String currencyCode) {

        Money toMoney() {
            return new Money(amount, currencyCode);
        }
    }
}
