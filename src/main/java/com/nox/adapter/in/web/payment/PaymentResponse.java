package com.nox.adapter.in.web.payment;

import com.nox.domain.payment.Money;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contract {@code Payment}: the partner's view. Account snapshots, the payment method and NOX's
 * internal id are never exposed; {@code paymentId} is payment-core's identifier.
 */
public record PaymentResponse(
        UUID userLinkId,
        String externalId,
        String paymentId,
        Instant creationDate,
        PaymentType type,
        MoneyResponse money,
        MoneyResponse fee,
        String transactionReference,
        PaymentStatus status,
        Instant expirationDate,
        Instant confirmedDate) {

    static PaymentResponse of(Payment payment) {
        return new PaymentResponse(
                payment.userLinkId(),
                payment.externalId(),
                payment.paymentCoreId(),
                payment.paymentCoreCreatedAt() != null ? payment.paymentCoreCreatedAt() : payment.createdAt(),
                payment.type(),
                MoneyResponse.of(payment.money()),
                MoneyResponse.of(payment.fee()),
                payment.transactionReference(),
                payment.status(),
                payment.expirationDate(),
                payment.confirmedDate());
    }

    /** Contract {@code Money}. */
    public record MoneyResponse(BigDecimal amount, String currencyCode) {

        static MoneyResponse of(Money money) {
            return money == null ? null : new MoneyResponse(money.amount(), money.currencyCode());
        }
    }
}
