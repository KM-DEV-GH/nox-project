package com.nox.application.service.payment;

import com.nox.application.port.in.payment.CancelPaymentUseCase;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.application.port.out.payment.PaymentProcessorPort.CancellationRequest;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Cancels a payment awaiting the OTP, serialized against confirmation (research.md D10). NOX reports
 * {@code CANCELLED} only when payment-core says so; a refusal leaves the payment pending, and an
 * answer that never comes leaves it {@code CANCELLATION_UNKNOWN}.
 */
@Service
public class CancelPaymentService implements CancelPaymentUseCase {

    private final PaymentActions actions;
    private final PaymentProcessorPort processor;

    public CancelPaymentService(
            PartnerLinkRepository links, PaymentRepository payments, PaymentProcessorPort processor, Clock clock) {
        this.actions = new PaymentActions(links, payments, clock);
        this.processor = processor;
    }

    @Override
    public Payment cancel(Command command) {
        Payment payment = actions.find(command.partnerId(), command.link(), command.paymentId());
        return actions.run(
                payment,
                PaymentStatus.CANCELLED,
                Payment::beginCancellation,
                claimed -> processor.cancel(new CancellationRequest(
                        claimed.paymentCoreId(),
                        claimed.providerUserIdSnapshot(),
                        claimed.accountIdSnapshot(),
                        claimed.money())),
                PaymentFailure.NOT_ACTIONABLE);
    }
}
