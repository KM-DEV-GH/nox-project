package com.nox.adapter.in.web.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Error catalogue (data-model.md). Each type has a fixed, generic {@code detail} that never
 * reveals which check failed (FR-014).
 */
public enum ProblemType {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "The request is malformed or failed validation."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid access token is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this resource."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The requested media type is not supported."),
    LINK_EXPIRED(HttpStatus.CONFLICT, "This link request has expired. Ask the provider to send a new one."),
    LINK_NOT_ACTIONABLE(HttpStatus.CONFLICT, "This link is not in a state that allows this action."),
    PAYMENT_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "The currency is not enabled, or the amount is outside the configured limits."),
    PAYMENT_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_CONTENT, "Payments of this type are not available for you."),
    PAYMENT_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "The payment was declined by the payment service."),
    PAYMENT_OTP_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "The one-time code was not accepted."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "This idempotency key was already used with different payment details."),
    EXTERNAL_ID_CONFLICT(HttpStatus.CONFLICT, "This external ID was already used for another payment."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "This payment is already being processed. Retry later."),
    PAYMENT_NOT_ACTIONABLE(HttpStatus.CONFLICT, "This payment is not in a state that allows this action."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please retry later."),
    PAYMENT_PROCESSOR_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "The payment service is temporarily unavailable. Please retry later."),
    PAYMENT_UNRESOLVED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The payment outcome could not be determined and is being reconciled. Do not resubmit it."),
    IDENTITY_PROVIDER_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable. Please retry later.");

    private static final String TYPE_PREFIX = "urn:problem:";

    private final HttpStatus status;
    private final String detail;

    ProblemType(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String type() {
        return TYPE_PREFIX + title();
    }

    public String detail() {
        return detail;
    }
}
