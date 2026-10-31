package com.nox.adapter.out.paymentcore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.domain.payment.ProcessorPayment;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls payment-core exactly as contracts/payment-core.md describes. Every call is bounded by
 * connect/read timeouts, guarded by a circuit breaker, and retried on transient failures (Spring's
 * {@link RetryTemplate}). Retrying is safe: initialization carries a stable {@code Idempotency-Key},
 * and payment-core deduplicates confirm and cancel. A client error is a rejection and is never
 * retried. Neither bodies, URLs nor exceptions are logged: they carry account identifiers, amounts
 * or the OTP.
 */
@Component
public class PaymentCoreAdapter implements PaymentProcessorPort {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentCoreAdapter.class);
    private static final String BASE_PATH = "/internal-payments/as-provider";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final Map<String, PaymentStatus> STATUSES = Map.ofEntries(
            Map.entry("INSERTED", PaymentStatus.AWAITING_OTP),
            Map.entry("PENDING", PaymentStatus.AWAITING_OTP),
            Map.entry("AWAITING_OTP", PaymentStatus.AWAITING_OTP),
            Map.entry("COMPLETED", PaymentStatus.CONFIRMED),
            Map.entry("CONFIRMED", PaymentStatus.CONFIRMED),
            Map.entry("SUCCESS", PaymentStatus.CONFIRMED),
            Map.entry("SUCCEEDED", PaymentStatus.CONFIRMED),
            Map.entry("CANCELLED", PaymentStatus.CANCELLED),
            Map.entry("CANCELED", PaymentStatus.CANCELLED),
            Map.entry("EXPIRED", PaymentStatus.EXPIRED),
            Map.entry("FAILED", PaymentStatus.FAILED),
            Map.entry("REJECTED", PaymentStatus.FAILED),
            Map.entry("DECLINED", PaymentStatus.FAILED));

    private final RestClient paymentCore;
    private final RetryTemplate retry;
    private final CircuitBreaker circuitBreaker;

    public PaymentCoreAdapter(PaymentCoreProperties properties, ClientRegistrationRepository registrations) {
        SimpleClientHttpRequestFactory requestFactory = requestFactory(properties);
        PaymentCoreProperties.CircuitBreaker breaker = properties.circuitBreaker();
        this.paymentCore = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(PaymentCoreAccessToken.interceptor(
                        registrations.findByRegistrationId(properties.registrationId()), requestFactory))
                .build();
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxAttempts() - 1L)
                .delay(properties.backoff())
                .multiplier(BACKOFF_MULTIPLIER)
                .includes(TransientFailure.class)
                .build());
        this.circuitBreaker = CircuitBreaker.of(
                "payment-core",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(breaker.failureRateThreshold())
                        .slidingWindowSize(breaker.slidingWindowSize())
                        .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                        .waitDurationInOpenState(breaker.waitDurationInOpenState())
                        .recordExceptions(TransientFailure.class)
                        .build());
    }

    /** Bounded connections, through the SOCKS5 proxy when enabled; shared by API and token requests, as for Owl. */
    static SimpleClientHttpRequestFactory requestFactory(PaymentCoreProperties properties) {
        if (properties.socks5Enabled() && properties.socks5Host().isBlank()) {
            throw new IllegalArgumentException("SOCKS5 proxy host is required when SOCKS5 is enabled");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (properties.socks5Enabled()) {
            factory.setProxy(new Proxy(
                    Proxy.Type.SOCKS, new InetSocketAddress(properties.socks5Host(), properties.socks5Port())));
        }
        factory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        factory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        return factory;
    }

    @Override
    public ProcessorResult initiate(InitiationRequest request) {
        String path = BASE_PATH + (request.type() == PaymentType.PAYIN ? "/pay-in" : "/pay-out");
        InitiationBody body = new InitiationBody(
                new Party(request.providerUserId(), request.accountId()),
                new Client(request.clientUserId()),
                MoneyBody.of(request.money()),
                request.paymentMethod(),
                request.referenceNumber());
        return call("initiation", () -> paymentCore
                .post()
                .uri(path)
                .header(IDEMPOTENCY_KEY, request.idempotencyKey().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Envelope.class));
    }

    @Override
    public ProcessorResult confirm(ConfirmationRequest request) {
        ConfirmationBody body = new ConfirmationBody(
                new Party(request.providerUserId(), request.accountId()), MoneyBody.of(request.money()), request.otp());
        return action("confirmation", request.paymentCoreId(), "confirm", body);
    }

    @Override
    public ProcessorResult cancel(CancellationRequest request) {
        CancellationBody body = new CancellationBody(
                new Party(request.providerUserId(), request.accountId()), MoneyBody.of(request.money()));
        return action("cancellation", request.paymentCoreId(), "cancel", body);
    }

    private ProcessorResult action(String operation, String paymentCoreId, String action, Object body) {
        return call(operation, () -> paymentCore
                .post()
                .uri(BASE_PATH + "/{paymentCoreId}/{action}", paymentCoreId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Envelope.class));
    }

    private ProcessorResult call(String operation, Supplier<Envelope> exchange) {
        AtomicBoolean attempted = new AtomicBoolean();
        try {
            ProcessorPayment payment = retry.execute(() -> circuitBreaker.executeSupplier(() -> {
                attempted.set(true);
                return once(exchange);
            }));
            return new ProcessorResult.Completed(payment);
        } catch (RetryException e) {
            return failed(operation, e.getCause(), attempted.get());
        }
    }

    private static ProcessorPayment once(Supplier<Envelope> exchange) {
        Envelope envelope;
        try {
            envelope = exchange.get();
        } catch (HttpClientErrorException e) {
            throw new Rejection(e.getStatusCode().value());
        } catch (RestClientException | OAuth2AuthorizationException e) {
            throw new TransientFailure();
        }
        if (envelope == null || envelope.data() == null || envelope.data().paymentId() == null) {
            throw new TransientFailure();
        }
        return envelope.data().toProcessorPayment();
    }

    private static ProcessorResult failed(String operation, Throwable cause, boolean attempted) {
        if (cause instanceof Rejection rejection) {
            LOG.atWarn()
                    .addKeyValue("status", rejection.status)
                    .log("payment-core rejected the {}", operation);
            return new ProcessorResult.Rejected();
        }
        if (cause instanceof CallNotPermittedException && !attempted) {
            LOG.warn("payment-core {} not attempted: circuit open", operation);
            return new ProcessorResult.NotAttempted();
        }
        LOG.warn("payment-core {} outcome unknown after retries", operation);
        return new ProcessorResult.Unknown();
    }

    static PaymentStatus mapStatus(String status) {
        return status == null ? null : STATUSES.get(status.toUpperCase(Locale.ROOT));
    }

    /** Worth retrying. Carries no cause: causes may contain bodies with identifiers or the OTP. */
    private static final class TransientFailure extends RuntimeException {

        private TransientFailure() {
            super("payment-core did not answer", null, false, false);
        }
    }

    /** Payment-core refused the request with a client error. */
    private static final class Rejection extends RuntimeException {

        private final int status;

        private Rejection(int status) {
            super("payment-core rejected the request", null, false, false);
            this.status = status;
        }
    }

    record InitiationBody(Party provider, Client client, MoneyBody money, String paymentMethod, String referenceNumber) {}

    record ConfirmationBody(Party provider, MoneyBody money, @JsonProperty("otp_code") String otpCode) {

        /** Never prints the OTP. */
        @Override
        public String toString() {
            return "ConfirmationBody[redacted]";
        }
    }

    record CancellationBody(Party provider, MoneyBody money) {}

    record Party(String userId, String accountId) {}

    record Client(String userId) {}

    record MoneyBody(BigDecimal amount, String currencyCode) {

        static MoneyBody of(Money money) {
            return new MoneyBody(money.amount(), money.currencyCode());
        }

        Money toMoney() {
            return amount == null || currencyCode == null ? null : new Money(amount, currencyCode);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Data data) {}

    /** Only the fields NOX maps; {@code account_id} and {@code counterparty_account_number} are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(
            @JsonProperty("payment_id") String paymentId,
            String status,
            MoneyBody money,
            MoneyBody fee,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("confirmed_at") Instant confirmedAt) {

        ProcessorPayment toProcessorPayment() {
            return new ProcessorPayment(
                    paymentId,
                    mapStatus(status),
                    money == null ? null : money.toMoney(),
                    fee == null ? null : fee.toMoney(),
                    createdAt,
                    expiresAt,
                    confirmedAt);
        }
    }
}
