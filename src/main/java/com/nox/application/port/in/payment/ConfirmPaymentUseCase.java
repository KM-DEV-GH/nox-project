package com.nox.application.port.in.payment;

import com.nox.domain.payment.Payment;
import java.util.UUID;

/** The partner submits the user's OTP for a payment awaiting it (spec FR-010–FR-014). */
public interface ConfirmPaymentUseCase {

    /**
     * @return the payment with payment-core's outcome, or the existing outcome of a confirmed payment
     * @throws PaymentException when the confirmation is refused or its outcome is unresolved
     */
    Payment confirm(Command command);

    /**
     * @param partnerId the authenticated partner
     * @param link the link the payment must belong to
     * @param paymentId payment-core's payment identifier
     * @param otp the user's one-time code; never logged or stored
     */
    record Command(UUID partnerId, LinkReference link, String paymentId, String otp) {

        @Override
        public String toString() {
            return "Command[partnerId=" + partnerId + ", paymentId=" + paymentId + ", otp=redacted]";
        }
    }
}
