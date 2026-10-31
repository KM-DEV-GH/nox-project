package com.nox.application.port.out.payment;

/** The partner already reserved this idempotency key, possibly in a concurrent request. */
public class DuplicatePaymentException extends RuntimeException {

    public DuplicatePaymentException() {
        super("The idempotency key is already in use", null, false, false);
    }
}
