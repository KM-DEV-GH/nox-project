package com.nox.adapter.out.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Runs one outbound exchange under Resilience4j: a circuit breaker around each attempt, and bounded
 * retries with exponential backoff around the breaker. A client error is a rejection, never retried
 * and never counted against the circuit. A server error, a timeout, an unavailable token endpoint or
 * an unusable answer is transient: retried, and counted.
 *
 * <p>Retrying is the caller's to justify: payment-core deduplicates by idempotency key, and the Owl
 * lookup only reads. Every failure surfaces as a cause-free {@link HttpCallFailure}.
 */
public final class ResilientCall {

    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ResilientCall(String name, HttpClientSettings settings) {
        CircuitBreakerSettings breaker = settings.circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of(
                name,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(breaker.failureRateThreshold())
                        .slidingWindowSize(breaker.slidingWindowSize())
                        .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                        .waitDurationInOpenState(breaker.waitDurationInOpenState())
                        .recordExceptions(TransientFailure.class)
                        .build());
        this.retry = Retry.of(
                name,
                RetryConfig.custom()
                        .maxAttempts(settings.maxAttempts())
                        .intervalFunction(IntervalFunction.ofExponentialBackoff(settings.backoff(), BACKOFF_MULTIPLIER))
                        .retryOnException(TransientFailure.class::isInstance)
                        .build());
    }

    /**
     * @param exchange one HTTP exchange
     * @param answered whether a non-null answer is usable; an unusable one is a transient failure
     * @throws HttpCallFailure when there is no usable answer
     */
    public <T> T execute(Supplier<T> exchange, Predicate<T> answered) {
        AtomicBoolean attempted = new AtomicBoolean();
        try {
            return retry.executeSupplier(() -> circuitBreaker.executeSupplier(() -> {
                attempted.set(true);
                return attempt(exchange, answered);
            }));
        } catch (Rejection e) {
            throw HttpCallFailure.rejected(e.status);
        } catch (CallNotPermittedException e) {
            // Open before the first attempt: nothing was sent. Opened mid-retry: an earlier attempt may have landed.
            throw attempted.get() ? HttpCallFailure.unknown() : HttpCallFailure.notAttempted();
        } catch (RuntimeException e) {
            throw HttpCallFailure.unknown();
        }
    }

    private static <T> T attempt(Supplier<T> exchange, Predicate<T> answer) {
        T body;
        try {
            body = exchange.get();
        } catch (HttpClientErrorException e) {
            throw new Rejection(e.getStatusCode().value());
        } catch (RestClientException | OAuth2AuthorizationException e) {
            throw new TransientFailure();
        }
        if (body == null || !answer.test(body)) {
            throw new TransientFailure();
        }
        return body;
    }

    /** Worth retrying. Carries no cause: causes may contain bodies with identifiers or the OTP. */
    private static final class TransientFailure extends RuntimeException {

        private TransientFailure() {
            super("remote service did not answer", null, false, false);
        }
    }

    /** The remote service refused the request with a client error. */
    private static final class Rejection extends RuntimeException {

        private final int status;

        private Rejection(int status) {
            super("remote service rejected the request", null, false, false);
            this.status = status;
        }
    }
}
