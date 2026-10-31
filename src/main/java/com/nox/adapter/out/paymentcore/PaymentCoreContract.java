package com.nox.adapter.out.paymentcore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.ProcessorPayment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** The request and response bodies of contracts/payment-core.md, and how its statuses map to NOX's. */
final class PaymentCoreContract {

    private static final Map<String, PaymentStatus> STATUSES = Map.ofEntries(
            Map.entry("INSERTED", PaymentStatus.AWAITING_OTP),
            Map.entry("PENDING", PaymentStatus.AWAITING_OTP),
            Map.entry("AWAITING_OTP", PaymentStatus.AWAITING_OTP),
            Map.entry("COMPLETED", PaymentStatus.CONFIRMED),
            Map.entry("CONFIRMED", PaymentStatus.CONFIRMED),
            Map.entry("SUCCESS", PaymentStatus.CONFIRMED),
            Map.entry("SUCCEEDED", PaymentStatus.CONFIRMED),
            Map.entry("CANCELLED", PaymentStatus.CANCELLED),
            Map.entry("CANCELED", PaymentStatus.CANCELLED),
            Map.entry("EXPIRED", PaymentStatus.EXPIRED),
            Map.entry("FAILED", PaymentStatus.FAILED),
            Map.entry("REJECTED", PaymentStatus.FAILED),
            Map.entry("DECLINED", PaymentStatus.FAILED));

    private PaymentCoreContract() {}

    static PaymentStatus mapStatus(String status) {
        return status == null ? null : STATUSES.get(status.toUpperCase(Locale.ROOT));
    }

    record InitiationBody(
            Party provider, Client client, MoneyBody money, String paymentMethod, String referenceNumber) {}

    record ConfirmationBody(
            Party provider,
            MoneyBody money,
            @JsonProperty("otp_code") String otpCode) {

        /** Never prints the OTP. */
        @Override
        public String toString() {
            return "ConfirmationBody[redacted]";
        }
    }

    record CancellationBody(Party provider, MoneyBody money) {}

    record Party(String userId, String accountId) {}

    record Client(String userId) {}

    record MoneyBody(BigDecimal amount, String currencyCode) {

        static MoneyBody of(Money money) {
            return new MoneyBody(money.amount(), money.currencyCode());
        }

        Money toMoney() {
            return amount == null || currencyCode == null ? null : new Money(amount, currencyCode);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Data data) {

        /** An answer without a payment id is no answer. */
        boolean hasPayment() {
            return data != null && data.paymentId() != null;
        }
    }

    /** Only the fields NOX maps; {@code account_id} and {@code counterparty_account_number} are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(
            @JsonProperty("payment_id") String paymentId,
            String status,
            MoneyBody money,
            MoneyBody fee,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("confirmed_at") Instant confirmedAt) {

        ProcessorPayment toProcessorPayment() {
            return new ProcessorPayment(
                    paymentId,
                    mapStatus(status),
                    money == null ? null : money.toMoney(),
                    fee == null ? null : fee.toMoney(),
                    createdAt,
                    expiresAt,
                    confirmedAt);
        }
    }
}
