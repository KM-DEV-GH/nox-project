package com.nox.application.port.out.payment;

/** The partner already has a payment with this external ID, possibly from a concurrent request. */
public class DuplicateExternalIdException extends RuntimeException {

    public DuplicateExternalIdException() {
        super("The external ID is already in use", null, false, false);
    }
}
