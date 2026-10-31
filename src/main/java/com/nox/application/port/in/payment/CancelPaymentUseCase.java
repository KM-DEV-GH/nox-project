package com.nox.application.port.in.payment;

import com.nox.domain.payment.Payment;
import java.util.UUID;

/** The partner cancels, on the user's behalf, a payment still awaiting the OTP (spec FR-018–FR-021). */
public interface CancelPaymentUseCase {

    /**
     * @return the payment with payment-core's outcome, or the existing outcome of a cancelled payment
     * @throws PaymentException when the cancellation is refused or its outcome is unresolved
     */
    Payment cancel(Command command);

    /**
     * @param partnerId the authenticated partner
     * @param link the link the payment must belong to
     * @param paymentId payment-core's payment identifier
     */
    record Command(UUID partnerId, LinkReference link, String paymentId) {}
}
