package com.nox.adapter.in.web.error;

import com.nox.application.port.in.payment.PaymentException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions raised after the security filters to problem responses (constitution Principle VI). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemResponseWriter problemWriter;

    public GlobalExceptionHandler(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return problemWriter.toResponseEntity(request, ProblemType.NOT_FOUND);
    }

    /** Invalid input of any kind gets the same generic answer; which check failed is not revealed. */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMediaTypeNotSupportedException.class
    })
    ResponseEntity<ProblemDetail> badRequest(HttpServletRequest request) {
        return problemWriter.toResponseEntity(request, ProblemType.BAD_REQUEST);
    }

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> problem(ProblemException problem, HttpServletRequest request) {
        return problemWriter.toResponseEntity(request, problem.type());
    }

    /** Logged by failure kind only: never amounts, account identifiers, tokens or OTPs. */
    @ExceptionHandler(PaymentException.class)
    ResponseEntity<ProblemDetail> payment(PaymentException exception, HttpServletRequest request) {
        ProblemType type = switch (exception.failure()) {
            case LINK_NOT_FOUND, PAYMENT_NOT_FOUND -> ProblemType.NOT_FOUND;
            case LINK_NOT_CONNECTED -> ProblemType.LINK_NOT_ACTIONABLE;
            case LIMIT_EXCEEDED -> ProblemType.PAYMENT_LIMIT_EXCEEDED;
            case NOT_CONFIGURED -> ProblemType.PAYMENT_NOT_CONFIGURED;
            case IDEMPOTENCY_CONFLICT -> ProblemType.IDEMPOTENCY_CONFLICT;
            case EXTERNAL_ID_CONFLICT -> ProblemType.EXTERNAL_ID_CONFLICT;
            case IN_PROGRESS -> ProblemType.PAYMENT_IN_PROGRESS;
            case NOT_ACTIONABLE -> ProblemType.PAYMENT_NOT_ACTIONABLE;
            case REJECTED -> ProblemType.PAYMENT_REJECTED;
            case OTP_REJECTED -> ProblemType.PAYMENT_OTP_REJECTED;
            case PROCESSOR_UNAVAILABLE -> ProblemType.PAYMENT_PROCESSOR_UNAVAILABLE;
            case UNRESOLVED -> ProblemType.PAYMENT_UNRESOLVED;
        };
        if (type.status().is5xxServerError()) {
            LOG.atWarn().addKeyValue("failure", exception.failure()).log("payment not completed");
        }
        return problemWriter.toResponseEntity(request, type);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> problem = problemWriter.toResponseEntity(request, ProblemType.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported == null || supported.isEmpty()) {
            return problem;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(problem.getHeaders());
        headers.setAllow(supported);
        return new ResponseEntity<>(problem.getBody(), headers, problem.getStatusCode());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> notAcceptable(HttpServletRequest request) {
        return problemWriter.toResponseEntity(request, ProblemType.NOT_ACCEPTABLE);
    }

    /** Re-thrown so Spring Security's filter chain answers with 403 (method security denials). */
    @ExceptionHandler(AccessDeniedException.class)
    void accessDenied(AccessDeniedException exception) {
        throw exception;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> internalError(Exception exception, HttpServletRequest request) {
        LOG.error("unexpected error", exception);
        return problemWriter.toResponseEntity(request, ProblemType.INTERNAL_ERROR);
    }
}
