package com.nox.adapter.in.web.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract {@code PaymentConfirmation}: the user's one-time code, write-only and never logged. */
public record PaymentConfirmationRequest(
        @NotBlank @Size(max = 64) String code) {

    @Override
    public String toString() {
        return "PaymentConfirmationRequest[code=redacted]";
    }
}
