package com.nox.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Retry, circuit breaking and failure classification, independent of any remote contract. */
class ResilientCallTest {

    private static final int MAX_ATTEMPTS = 3;
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void returnsTheFirstUsableAnswerWithoutRetrying() {
        ResilientCall call = call(100);

        String answer = call.execute(() -> "ok" + calls.incrementAndGet(), body -> true);

        assertThat(answer).isEqualTo("ok1");
    }

    @Test
    void retriesATransientFailureUntilItAnswers() {
        ResilientCall call = call(100);

        String answer = call.execute(
                () -> {
                    if (calls.incrementAndGet() < MAX_ATTEMPTS) {
                        throw new ResourceAccessException("timeout");
                    }
                    return "ok";
                },
                body -> true);

        assertThat(answer).isEqualTo("ok");
        assertThat(calls).hasValue(MAX_ATTEMPTS);
    }

    @Test
    void aPersistentFailureIsUnknownAfterTheBoundedAttempts() {
        ResilientCall call = call(100);

        assertThatThrownBy(() -> call.execute(this::serverError, body -> true))
                .isInstanceOfSatisfying(
                        HttpCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(HttpCallFailure.Kind.UNKNOWN));
        assertThat(calls).hasValue(MAX_ATTEMPTS);
    }

    @Test
    void aMissingOrUnusableAnswerIsRetriedAsTransient() {
        ResilientCall call = call(100);

        assertThatThrownBy(() -> call.execute(() -> null, body -> true)).isInstanceOf(HttpCallFailure.class);
        assertThatThrownBy(() -> call.execute(() -> "unusable", body -> false)).isInstanceOf(HttpCallFailure.class);
    }

    @Test
    void aClientErrorIsRejectedAtOnceWithItsStatus() {
        ResilientCall call = call(100);

        assertThatThrownBy(() -> call.execute(
                        () -> {
                            calls.incrementAndGet();
                            throw clientError(HttpStatus.UNPROCESSABLE_CONTENT);
                        },
                        body -> true))
                .isInstanceOfSatisfying(HttpCallFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(HttpCallFailure.Kind.REJECTED);
                    assertThat(failure.status()).isEqualTo(422);
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void aFailureCarriesNeitherACauseNorTheMessageOfTheOriginal() {
        ResilientCall call = call(100);

        assertThatThrownBy(() -> call.execute(
                        () -> {
                            throw new ResourceAccessException("GET https://owl/user?email=secret@example.com");
                        },
                        body -> true))
                .isInstanceOfSatisfying(HttpCallFailure.class, failure -> {
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("secret");
                    assertThat(failure.getStackTrace()).isEmpty();
                });
    }

    @Test
    void anOpenCircuitIsNotAttemptedAndSendsNothing() {
        ResilientCall call = call(2);
        assertThatThrownBy(() -> call.execute(this::serverError, body -> true)).isInstanceOf(HttpCallFailure.class);
        int sent = calls.get();

        assertThatThrownBy(() -> call.execute(this::serverError, body -> true))
                .isInstanceOfSatisfying(
                        HttpCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(HttpCallFailure.Kind.NOT_ATTEMPTED));
        assertThat(calls).hasValue(sent);
    }

    /** The circuit tripping between attempts of one call: an earlier attempt may have landed, so not "not attempted". */
    @Test
    void aCircuitThatOpensBetweenRetriesIsUnknown() {
        ResilientCall call = call(2);

        assertThatThrownBy(() -> call.execute(this::serverError, body -> true))
                .isInstanceOfSatisfying(
                        HttpCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(HttpCallFailure.Kind.UNKNOWN));
        assertThat(calls).hasValue(2);
    }

    @Test
    void aRejectionDoesNotCountAgainstTheCircuit() {
        ResilientCall call = call(2);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> call.execute(
                            () -> {
                                throw clientError(HttpStatus.BAD_REQUEST);
                            },
                            body -> true))
                    .isInstanceOfSatisfying(
                            HttpCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(HttpCallFailure.Kind.REJECTED));
        }

        assertThat(call.execute(() -> "ok", body -> true)).isEqualTo("ok");
    }

    private String serverError() {
        calls.incrementAndGet();
        throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null);
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, "no", null, null, null);
    }

    private static ResilientCall call(int minimumNumberOfCalls) {
        return new ResilientCall(
                "test-" + System.nanoTime(),
                settings(new CircuitBreakerSettings(
                        50, minimumNumberOfCalls, minimumNumberOfCalls, Duration.ofMinutes(1))));
    }

    private static HttpClientSettings settings(CircuitBreakerSettings circuitBreaker) {
        return new HttpClientSettings() {
            @Override
            public Duration connectTimeout() {
                return Duration.ofSeconds(1);
            }

            @Override
            public Duration readTimeout() {
                return Duration.ofSeconds(1);
            }

            @Override
            public int maxAttempts() {
                return MAX_ATTEMPTS;
            }

            @Override
            public Duration backoff() {
                return Duration.ofMillis(1);
            }

            @Override
            public CircuitBreakerSettings circuitBreaker() {
                return circuitBreaker;
            }

            @Override
            public boolean socks5Enabled() {
                return false;
            }

            @Override
            public String socks5Host() {
                return "";
            }

            @Override
            public int socks5Port() {
                return 1080;
            }
        };
    }
}
