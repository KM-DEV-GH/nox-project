package com.nox.application.service.payment;

import com.nox.application.port.in.payment.ConfirmPaymentUseCase;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.application.port.out.payment.PaymentProcessorPort.ConfirmationRequest;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Confirms a payment with the user's OTP, using the account and money captured at initiation
 * (FR-007, FR-011). A refused OTP leaves the payment awaiting another; an answer that never comes
 * leaves it {@code CONFIRMATION_UNKNOWN}, never retried by a later request (research.md D5).
 */
@Service
public class ConfirmPaymentService implements ConfirmPaymentUseCase {

    private final PaymentActions actions;
    private final PaymentProcessorPort processor;

    public ConfirmPaymentService(
            PartnerLinkRepository links, PaymentRepository payments, PaymentProcessorPort processor, Clock clock) {
        this.actions = new PaymentActions(links, payments, clock);
        this.processor = processor;
    }

    @Override
    public Payment confirm(Command command) {
        Payment payment = actions.find(command.partnerId(), command.link(), command.paymentId());
        return actions.run(
                payment,
                PaymentStatus.CONFIRMED,
                Payment::beginConfirmation,
                claimed -> processor.confirm(new ConfirmationRequest(
                        claimed.paymentCoreId(),
                        claimed.providerUserIdSnapshot(),
                        claimed.accountIdSnapshot(),
                        claimed.money(),
                        command.otp())),
                PaymentFailure.OTP_REJECTED);
    }
}
