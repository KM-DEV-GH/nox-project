package com.nox.adapter.out.http;

/**
 * A call that did not produce an answer, and what is known about it. Carries no cause and no stack
 * trace: causes may contain bodies or URLs with account identifiers, amounts, an OTP or an email.
 */
public final class HttpCallFailure extends RuntimeException {

    /** What the caller may conclude about the remote side. */
    public enum Kind {
        /** The remote service refused the request with a client error; it was not retried. */
        REJECTED,
        /** The circuit was open before any attempt: nothing was sent. */
        NOT_ATTEMPTED,
        /** Every attempt failed or went unanswered: the remote side may or may not have acted. */
        UNKNOWN
    }

    private final Kind kind;
    private final int status;

    private HttpCallFailure(Kind kind, int status) {
        super("outbound call " + kind, null, false, false);
        this.kind = kind;
        this.status = status;
    }

    static HttpCallFailure rejected(int status) {
        return new HttpCallFailure(Kind.REJECTED, status);
    }

    static HttpCallFailure notAttempted() {
        return new HttpCallFailure(Kind.NOT_ATTEMPTED, 0);
    }

    static HttpCallFailure unknown() {
        return new HttpCallFailure(Kind.UNKNOWN, 0);
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status of a {@link Kind#REJECTED} call; 0 otherwise. */
    public int status() {
        return status;
    }
}
