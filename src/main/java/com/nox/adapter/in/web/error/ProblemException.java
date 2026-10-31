package com.nox.adapter.in.web.error;

/**
 * Raised by a controller to answer with a catalogued problem; {@link GlobalExceptionHandler} writes
 * the body, so controllers never build error bodies themselves (constitution Principle VI).
 */
public class ProblemException extends RuntimeException {

    private final ProblemType type;

    public ProblemException(ProblemType type) {
        super(type.title(), null, false, false);
        this.type = type;
    }

    public ProblemType type() {
        return type;
    }
}
