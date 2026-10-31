package com.nox.application.port.out.payment;

import java.util.UUID;

/** Another transaction changed the payment first (optimistic locking). */
public class ConcurrentPaymentUpdateException extends RuntimeException {

    public ConcurrentPaymentUpdateException(UUID paymentId) {
        super("Payment " + paymentId + " was changed concurrently");
    }
}
