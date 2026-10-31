package com.nox.application.port.in.payment;

/** A payment use case could not complete for {@link #failure()}. Carries no payment details. */
public class PaymentException extends RuntimeException {

    private final PaymentFailure failure;

    public PaymentException(PaymentFailure failure) {
        super(failure.name(), null, false, false);
        this.failure = failure;
    }

    public PaymentFailure failure() {
        return failure;
    }
}
