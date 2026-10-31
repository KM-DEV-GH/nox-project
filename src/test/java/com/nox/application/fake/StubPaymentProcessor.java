package com.nox.application.fake;

import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.application.port.out.payment.PaymentProcessorPort.CancellationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ConfirmationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.InitiationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.ProcessorPayment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Answers every call with a configurable result and records the requests. By default it accepts:
 * payment-core echoes the money, charges {@link #FEE}, and assigns {@link #PAYMENT_CORE_ID}.
 */
public class StubPaymentProcessor implements PaymentProcessorPort {

    public static final String PAYMENT_CORE_ID = "pc-1";
    public static final Money FEE = Money.of("1.50", "EUR");
    public static final Instant CREATED_AT = Instant.parse("2026-10-31T10:00:01Z");
    public static final Instant EXPIRES_AT = Instant.parse("2026-10-31T10:10:00Z");
    public static final Instant CONFIRMED_AT = Instant.parse("2026-10-31T10:05:00Z");

    private final List<InitiationRequest> initiations = new ArrayList<>();
    private final List<ConfirmationRequest> confirmations = new ArrayList<>();
    private final List<CancellationRequest> cancellations = new ArrayList<>();
    private Function<CancellationRequest, ProcessorResult> onCancel =
            request -> outcome(request.paymentCoreId(), PaymentStatus.CANCELLED, request.money());
    private Function<InitiationRequest, ProcessorResult> onInitiate = request -> accepted(request.money());
    private Function<ConfirmationRequest, ProcessorResult> onConfirm =
            request -> outcome(request.paymentCoreId(), PaymentStatus.CONFIRMED, request.money());

    public static ProcessorResult accepted(Money money) {
        return new ProcessorResult.Completed(new ProcessorPayment(
                PAYMENT_CORE_ID, PaymentStatus.AWAITING_OTP, money, FEE, CREATED_AT, EXPIRES_AT, null));
    }

    /** Payment-core's answer to a confirmation or cancellation. */
    public static ProcessorResult outcome(String paymentCoreId, PaymentStatus status, Money money) {
        return new ProcessorResult.Completed(new ProcessorPayment(
                paymentCoreId,
                status,
                money,
                FEE,
                CREATED_AT,
                EXPIRES_AT,
                status == PaymentStatus.CONFIRMED ? CONFIRMED_AT : null));
    }

    public void onConfirm(Function<ConfirmationRequest, ProcessorResult> answer) {
        onConfirm = answer;
    }

    public void onConfirm(ProcessorResult result) {
        onConfirm = request -> result;
    }

    public List<ConfirmationRequest> confirmations() {
        return List.copyOf(confirmations);
    }

    @Override
    public ProcessorResult confirm(ConfirmationRequest request) {
        confirmations.add(request);
        return onConfirm.apply(request);
    }

    public void onCancel(Function<CancellationRequest, ProcessorResult> answer) {
        onCancel = answer;
    }

    public void onCancel(ProcessorResult result) {
        onCancel = request -> result;
    }

    public List<CancellationRequest> cancellations() {
        return List.copyOf(cancellations);
    }

    @Override
    public ProcessorResult cancel(CancellationRequest request) {
        cancellations.add(request);
        return onCancel.apply(request);
    }

    public void onInitiate(Function<InitiationRequest, ProcessorResult> answer) {
        onInitiate = answer;
    }

    public void onInitiate(ProcessorResult result) {
        onInitiate = request -> result;
    }

    public List<InitiationRequest> initiations() {
        return List.copyOf(initiations);
    }

    @Override
    public ProcessorResult initiate(InitiationRequest request) {
        initiations.add(request);
        return onInitiate.apply(request);
    }
}
