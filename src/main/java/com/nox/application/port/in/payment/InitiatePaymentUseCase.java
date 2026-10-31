package com.nox.application.port.in.payment;

import com.nox.domain.payment.Money;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentType;
import java.util.UUID;

/** A partner initiates a pay-in or pay-out for a connected link (spec FR-001–FR-009). */
public interface InitiatePaymentUseCase {

    /** @throws PaymentException when the payment is refused or its outcome is unresolved */
    Initiation initiate(Command command);

    /**
     * @param partnerId the authenticated partner; never taken from the request body
     * @param link the user's link
     * @param idempotencyKey unique within the partner
     */
    record Command(
            UUID partnerId,
            LinkReference link,
            String idempotencyKey,
            PaymentType type,
            Money money,
            String externalId,
            String transactionReference,
            String description) {}

    /**
     * @param payment the payment, as payment-core reported it
     * @param created {@code false} when an identical earlier request is being replayed
     */
    record Initiation(Payment payment, boolean created) {}
}
