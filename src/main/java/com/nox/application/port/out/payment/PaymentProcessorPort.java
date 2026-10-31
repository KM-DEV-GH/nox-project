package com.nox.application.port.out.payment;

import com.nox.domain.payment.Money;
import com.nox.domain.payment.PaymentType;
import com.nox.domain.payment.ProcessorPayment;
import java.util.UUID;

/** Payment-core, the authority for payment outcomes (contracts/payment-core.md). */
public interface PaymentProcessorPort {

    ProcessorResult initiate(InitiationRequest request);

    ProcessorResult confirm(ConfirmationRequest request);

    ProcessorResult cancel(CancellationRequest request);

    /**
     * @param idempotencyKey NOX's payment id; the same for every attempt of this initiation
     * @param paymentMethod the configured method for {@code type}
     * @param providerUserId the account snapshot's provider user
     * @param accountId the account snapshot
     * @param clientUserId the linked user's Owl {@code user_id}
     * @param referenceNumber the partner's transaction reference
     */
    record InitiationRequest(
            UUID idempotencyKey,
            PaymentType type,
            String paymentMethod,
            String providerUserId,
            String accountId,
            String clientUserId,
            Money money,
            String referenceNumber) {

        /** Never prints account or user identifiers or the amount, which must not reach logs. */
        @Override
        public String toString() {
            return "InitiationRequest[idempotencyKey=" + idempotencyKey + ", type=" + type + ", redacted]";
        }
    }

    /**
     * Confirmation of a payment with the account snapshot and money captured at initiation (FR-007, FR-011).
     *
     * @param otp the user's one-time code
     */
    record ConfirmationRequest(String paymentCoreId, String providerUserId, String accountId, Money money, String otp) {

        /** Never prints the OTP, the account identifiers or the amount. */
        @Override
        public String toString() {
            return "ConfirmationRequest[paymentCoreId=" + paymentCoreId + ", redacted]";
        }
    }

    /** Cancellation of a payment with the account snapshot and money captured at initiation. */
    record CancellationRequest(String paymentCoreId, String providerUserId, String accountId, Money money) {

        /** Never prints the account identifiers or the amount. */
        @Override
        public String toString() {
            return "CancellationRequest[paymentCoreId=" + paymentCoreId + ", redacted]";
        }
    }

    /** What payment-core did, as far as NOX can tell. */
    sealed interface ProcessorResult {

        /** Payment-core answered with its view of the payment. */
        record Completed(ProcessorPayment payment) implements ProcessorResult {}

        /** Payment-core refused the request (a client error); nothing changed there. */
        record Rejected() implements ProcessorResult {}

        /** Payment-core was not called: the circuit is open. */
        record NotAttempted() implements ProcessorResult {}

        /** No authoritative answer after the bounded retries: payment-core may or may not have acted. */
        record Unknown() implements ProcessorResult {}
    }
}
