package com.nox.application.service.payment;

import com.nox.application.port.in.payment.LinkReference;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.payment.ConcurrentPaymentUpdateException;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * What confirmation and cancellation share (data-model.md, Payment State Transitions; spec FR-019):
 * the payment is found only through the partner's connected link, claimed by a committed state
 * change before payment-core is called, so a concurrent confirm or cancel loses the optimistic lock
 * or finds it in flight, and payment-core's answer is recorded in a second short transaction.
 */
final class PaymentActions {

    private static final Set<PaymentStatus> UNRESOLVED = EnumSet.of(
            PaymentStatus.INITIATION_UNKNOWN, PaymentStatus.CONFIRMATION_UNKNOWN, PaymentStatus.CANCELLATION_UNKNOWN);

    private final PaymentLinks links;
    private final PaymentRepository payments;
    private final Clock clock;

    PaymentActions(PartnerLinkRepository links, PaymentRepository payments, Clock clock) {
        this.links = new PaymentLinks(links);
        this.payments = payments;
        this.clock = clock;
    }

    /** The payment, if it belongs to the partner's resolved link and that link is still connected. */
    Payment find(UUID partnerId, LinkReference reference, String paymentId) {
        PartnerLink link = links.resolve(partnerId, reference);
        Payment payment = payments.findByPaymentCoreId(paymentId)
                .filter(found -> found.partnerId().equals(partnerId))
                .filter(found -> found.userLinkId().equals(link.id()))
                .orElseThrow(() -> new PaymentException(PaymentFailure.PAYMENT_NOT_FOUND));
        PaymentLinks.requireConnected(link);
        return payment;
    }

    /**
     * Runs one confirm or cancel: an already {@code done} payment is returned as it is (FR-013,
     * FR-020); otherwise {@code claim} must move it out of {@code AWAITING_OTP} before {@code call}.
     *
     * @param whenRejected the failure reported when payment-core refuses the action
     */
    Payment run(
            Payment payment,
            PaymentStatus done,
            BiPredicate<Payment, Instant> claim,
            Function<Payment, ProcessorResult> call,
            PaymentFailure whenRejected) {
        if (payment.status() == done) {
            return payment;
        }
        if (!claim.test(payment, clock.instant())) {
            throw new PaymentException(refusal(payment.status()));
        }
        Payment claimed;
        try {
            claimed = payments.save(payment);
        } catch (ConcurrentPaymentUpdateException e) {
            throw new PaymentException(PaymentFailure.NOT_ACTIONABLE);
        }
        switch (call.apply(claimed)) {
            case ProcessorResult.Completed completed -> {
                claimed.recordOutcome(completed.payment(), clock.instant());
                Payment stored = payments.save(claimed);
                if (UNRESOLVED.contains(stored.status())) {
                    throw new PaymentException(PaymentFailure.UNRESOLVED);
                }
                return stored;
            }
            case ProcessorResult.Rejected rejected -> {
                claimed.returnToAwaitingOtp(clock.instant());
                payments.save(claimed);
                throw new PaymentException(whenRejected);
            }
            case ProcessorResult.NotAttempted notAttempted -> {
                claimed.returnToAwaitingOtp(clock.instant());
                payments.save(claimed);
                throw new PaymentException(PaymentFailure.PROCESSOR_UNAVAILABLE);
            }
            case ProcessorResult.Unknown unknown -> {
                claimed.outcomeUnknown(clock.instant());
                payments.save(claimed);
                throw new PaymentException(PaymentFailure.UNRESOLVED);
            }
        }
    }

    private static PaymentFailure refusal(PaymentStatus status) {
        if (status.isInFlight()) {
            return PaymentFailure.IN_PROGRESS;
        }
        return UNRESOLVED.contains(status) ? PaymentFailure.UNRESOLVED : PaymentFailure.NOT_ACTIONABLE;
    }
}
