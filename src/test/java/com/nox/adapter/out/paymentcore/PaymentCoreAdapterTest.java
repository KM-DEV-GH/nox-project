package com.nox.adapter.out.paymentcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nox.adapter.out.http.CircuitBreakerSettings;
import com.nox.application.port.out.payment.PaymentProcessorPort.CancellationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ConfirmationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.InitiationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.domain.payment.ProcessorPayment;
import com.nox.integration.support.PaymentCoreWireMockSupport;
import com.nox.support.CapturedLogs;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** contracts/payment-core.md: request/response mapping, OAuth2, timeouts, retries and the circuit breaker. */
class PaymentCoreAdapterTest {

    private static final String TOKEN_PATH = "/oauth/token";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final int MAX_ATTEMPTS = 2;
    private static final Money EUR_123 = Money.of("123.45", "EUR");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WireMockServer server =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private PaymentCoreWireMockSupport paymentCore;
    private PaymentCoreAdapter adapter;
    private CapturedLogs logs;

    @BeforeEach
    void start() {
        server.start();
        paymentCore = new PaymentCoreWireMockSupport(new WireMock(server.port()), TOKEN_PATH);
        paymentCore.stubTokenEndpoint();
        adapter = adapter(100);
        logs = CapturedLogs.of(PaymentCoreAdapter.class);
    }

    @AfterEach
    void stop() {
        logs.close();
        server.stop();
    }

    @Test
    void initiatesAPayinWithABearerTokenAndTheStableIdempotencyKey() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(PaymentType.PAYIN, reference, "pc-1", "123.45", "EUR");
        InitiationRequest request = request(PaymentType.PAYIN, reference);

        ProcessorResult result = adapter.initiate(request);

        assertThat(result)
                .isEqualTo(new ProcessorResult.Completed(new ProcessorPayment(
                        "pc-1",
                        PaymentStatus.AWAITING_OTP,
                        Money.of("123.45", "EUR"),
                        Money.of("1.5", "EUR"),
                        Instant.parse(PaymentCoreWireMockSupport.CREATED_AT),
                        Instant.parse(PaymentCoreWireMockSupport.EXPIRES_AT),
                        null)));
        LoggedRequest sent =
                paymentCore.initiations(PaymentType.PAYIN, reference).getFirst();
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + PaymentCoreWireMockSupport.ACCESS_TOKEN);
        assertThat(sent.getHeader("Idempotency-Key"))
                .isEqualTo(request.idempotencyKey().toString());
        assertThat(sent.containsHeader("api-key")).isFalse();
        JsonNode body = JSON.readTree(sent.getBodyAsString());
        assertThat(body.at("/provider/userId").asString()).isEqualTo("provider-1");
        assertThat(body.at("/provider/accountId").asString()).isEqualTo("account-1");
        assertThat(body.at("/client/userId").asString()).isEqualTo("owl-user-1");
        assertThat(body.at("/money/amount").decimalValue()).isEqualByComparingTo("123.45");
        assertThat(body.at("/money/currencyCode").asString()).isEqualTo("EUR");
        assertThat(body.at("/paymentMethod").asString()).isEqualTo("BANK_PAYIN");
        assertThat(body.at("/referenceNumber").asString()).isEqualTo(reference);
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder("provider", "client", "money", "paymentMethod", "referenceNumber");
        assertNothingSensitiveLogged();
    }

    /** T020: pay-out goes to its own path with the pay-out method. */
    @Test
    void initiatesAPayoutOnThePayoutPath() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(PaymentType.PAYOUT, reference, "pc-out", "123.45", "EUR");

        ProcessorResult result = adapter.initiate(request(PaymentType.PAYOUT, reference));

        assertThat(result).isInstanceOfSatisfying(ProcessorResult.Completed.class, completed -> {
            assertThat(completed.payment().paymentCoreId()).isEqualTo("pc-out");
            assertThat(completed.payment().status()).isEqualTo(PaymentStatus.AWAITING_OTP);
            assertThat(completed.payment().fee()).isEqualTo(Money.of("1.5", "EUR"));
        });
        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).isEmpty();
        JsonNode body = JSON.readTree(paymentCore
                .initiations(PaymentType.PAYOUT, reference)
                .getFirst()
                .getBodyAsString());
        assertThat(body.at("/paymentMethod").asString()).isEqualTo("BANK_PAYOUT");
        assertThat(body.at("/provider/accountId").asString()).isEqualTo("account-1");
    }

    @Test
    void aTransientFailureIsRetriedWithTheSameIdempotencyKey() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationFailsOnceThenAccepted(PaymentType.PAYIN, reference, "pc-2", "123.45", "EUR");
        InitiationRequest request = request(PaymentType.PAYIN, reference);

        ProcessorResult result = adapter.initiate(request);

        assertThat(result).isInstanceOf(ProcessorResult.Completed.class);
        List<LoggedRequest> sent = paymentCore.initiations(PaymentType.PAYIN, reference);
        assertThat(sent).hasSize(2);
        assertThat(sent)
                .extracting(attempt -> attempt.getHeader("Idempotency-Key"))
                .containsOnly(request.idempotencyKey().toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void aClientErrorIsARejectionWithoutRetry(int status) {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationStatus(PaymentType.PAYIN, reference, status);

        assertThat(adapter.initiate(request(PaymentType.PAYIN, reference))).isEqualTo(new ProcessorResult.Rejected());
        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void aPersistentServerErrorIsUnknownAfterTheBoundedRetries(int status) {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationStatus(PaymentType.PAYIN, reference, status);

        assertThat(adapter.initiate(request(PaymentType.PAYIN, reference))).isEqualTo(new ProcessorResult.Unknown());
        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).hasSize(MAX_ATTEMPTS);
    }

    @Test
    void aSlowAnswerTimesOutAndIsUnknown() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationSlow(PaymentType.PAYIN, reference, READ_TIMEOUT.multipliedBy(3));

        long started = System.nanoTime();
        ProcessorResult result = adapter.initiate(request(PaymentType.PAYIN, reference));

        assertThat(result).isEqualTo(new ProcessorResult.Unknown());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertNothingSensitiveLogged();
    }

    @Test
    void anUnrecognisedStatusIsPassedOnAsUnrecognised() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAnswers(PaymentType.PAYIN, reference, "pc-3", "SOMETHING_NEW", "123.45", "EUR");

        ProcessorResult result = adapter.initiate(request(PaymentType.PAYIN, reference));

        assertThat(result)
                .isInstanceOfSatisfying(
                        ProcessorResult.Completed.class,
                        completed -> assertThat(completed.payment().status()).isNull());
    }

    /** Once open, the circuit refuses calls without contacting payment-core. */
    @Test
    void anOpenCircuitRejectsCallsWithoutSendingThem() {
        adapter = adapter(2);
        String failing = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationStatus(PaymentType.PAYIN, failing, 503);
        adapter.initiate(request(PaymentType.PAYIN, failing));
        String next = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(PaymentType.PAYIN, next, "pc-4", "123.45", "EUR");

        ProcessorResult result = adapter.initiate(request(PaymentType.PAYIN, next));

        assertThat(result).isEqualTo(new ProcessorResult.NotAttempted());
        assertThat(paymentCore.initiations(PaymentType.PAYIN, next)).isEmpty();
    }

    /** T025: the OTP and the original account snapshot reach payment-core; the answer is mapped. */
    @Test
    void confirmsWithTheOtpAndTheSnapshot() {
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.confirmationAnswers(paymentCoreId, "COMPLETED", "123.45", "EUR");

        ProcessorResult result = adapter.confirm(confirmation(paymentCoreId, "123456"));

        assertThat(result).isInstanceOfSatisfying(ProcessorResult.Completed.class, completed -> {
            assertThat(completed.payment().status()).isEqualTo(PaymentStatus.CONFIRMED);
            assertThat(completed.payment().confirmedAt())
                    .isEqualTo(Instant.parse(PaymentCoreWireMockSupport.CONFIRMED_AT));
        });
        LoggedRequest sent = paymentCore.confirmations(paymentCoreId).getFirst();
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + PaymentCoreWireMockSupport.ACCESS_TOKEN);
        assertThat(sent.containsHeader("api-key")).isFalse();
        JsonNode body = JSON.readTree(sent.getBodyAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("provider", "money", "otp_code");
        assertThat(body.at("/provider/userId").asString()).isEqualTo("provider-1");
        assertThat(body.at("/provider/accountId").asString()).isEqualTo("account-1");
        assertThat(body.at("/money/amount").decimalValue()).isEqualByComparingTo("123.45");
        assertThat(body.at("/otp_code").asString()).isEqualTo("123456");
        assertThat(logs.allText()).doesNotContain("123456");
        assertNothingSensitiveLogged();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 409, 422})
    void aRefusedOtpIsARejectionWithoutRetry(int status) {
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.confirmationStatus(paymentCoreId, status);

        assertThat(adapter.confirm(confirmation(paymentCoreId, "000000"))).isEqualTo(new ProcessorResult.Rejected());
        assertThat(paymentCore.confirmations(paymentCoreId)).hasSize(1);
    }

    /** Payment-core deduplicates confirm (owner-confirmed), so a bounded retry of the same call is safe. */
    @Test
    void aConfirmationWithoutAnAnswerIsRetriedThenUnknown() {
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.confirmationSlow(paymentCoreId, READ_TIMEOUT.multipliedBy(3));

        assertThat(adapter.confirm(confirmation(paymentCoreId, "123456"))).isEqualTo(new ProcessorResult.Unknown());
        assertThat(paymentCore.confirmations(paymentCoreId))
                .hasSize(MAX_ATTEMPTS)
                .extracting(LoggedRequest::getBodyAsString)
                .containsOnly(
                        paymentCore.confirmations(paymentCoreId).getFirst().getBodyAsString());
        assertThat(logs.allText()).doesNotContain("123456");
    }

    /** T032: cancel carries the snapshot and money but no OTP. */
    @Test
    void cancelsWithTheSnapshotAndMapsTheOutcome() {
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.cancellationAnswers(paymentCoreId, "CANCELED", "123.45", "EUR");

        ProcessorResult result = adapter.cancel(cancellation(paymentCoreId));

        assertThat(result)
                .isInstanceOfSatisfying(
                        ProcessorResult.Completed.class,
                        completed -> assertThat(completed.payment().status()).isEqualTo(PaymentStatus.CANCELLED));
        LoggedRequest sent = paymentCore.cancellations(paymentCoreId).getFirst();
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + PaymentCoreWireMockSupport.ACCESS_TOKEN);
        assertThat(sent.containsHeader("api-key")).isFalse();
        JsonNode body = JSON.readTree(sent.getBodyAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("provider", "money");
        assertThat(body.at("/provider/accountId").asString()).isEqualTo("account-1");
        assertThat(body.at("/money/amount").decimalValue()).isEqualByComparingTo("123.45");
        assertNothingSensitiveLogged();
    }

    @Test
    void aRefusedCancellationIsARejectionWithoutRetry() {
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.cancellationStatus(paymentCoreId, 409);

        assertThat(adapter.cancel(cancellation(paymentCoreId))).isEqualTo(new ProcessorResult.Rejected());
        assertThat(paymentCore.cancellations(paymentCoreId)).hasSize(1);
    }

    /** Payment-core deduplicates cancel (owner-confirmed), so the same call is retried, then unknown. */
    @Test
    void aCancellationWithoutAnAnswerIsRetriedThenUnknown() {
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.cancellationSlow(paymentCoreId, READ_TIMEOUT.multipliedBy(3));

        assertThat(adapter.cancel(cancellation(paymentCoreId))).isEqualTo(new ProcessorResult.Unknown());
        assertThat(paymentCore.cancellations(paymentCoreId)).hasSize(MAX_ATTEMPTS);
    }

    @Test
    void theConfirmationRequestNeverPrintsTheOtp() {
        assertThat(confirmation("pc-1", "987654").toString()).doesNotContain("987654", "account-1", "123.45");
    }

    @Test
    void socks5NeedsAHost() {
        assertThatThrownBy(() -> adapter(100, true, "", 1080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOCKS5");
    }

    /**
     * With SOCKS5 enabled, neither the token request nor the payment call goes to payment-core
     * directly: through an unreachable proxy, payment-core receives nothing and the outcome is unknown.
     */
    @Test
    void socks5RoutesTokenAndPaymentRequestsThroughTheProxy() throws IOException {
        adapter = adapter(100, true, "localhost", unusedPort());
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(PaymentType.PAYIN, reference, "pc-proxied", "123.45", "EUR");

        ProcessorResult result = adapter.initiate(request(PaymentType.PAYIN, reference));

        assertThat(result).isEqualTo(new ProcessorResult.Unknown());
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void theRequestNeverPrintsItsIdentifiers() {
        InitiationRequest request = request(PaymentType.PAYIN, "REF-1");

        assertThat(request.toString()).doesNotContain("account-1", "provider-1", "owl-user-1", "123.45");
    }

    private PaymentCoreAdapter adapter(int minimumNumberOfCalls) {
        return adapter(minimumNumberOfCalls, false, "", 1080);
    }

    private PaymentCoreAdapter adapter(
            int minimumNumberOfCalls, boolean socks5Enabled, String socks5Host, int socks5Port) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("payment-core")
                .clientId("nox-payments-test")
                .clientSecret("test-client-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(server.baseUrl() + TOKEN_PATH)
                .build();
        PaymentCoreProperties properties = new PaymentCoreProperties(
                server.baseUrl(),
                "payment-core",
                Duration.ofSeconds(1),
                READ_TIMEOUT,
                MAX_ATTEMPTS,
                Duration.ofMillis(10),
                new CircuitBreakerSettings(50, minimumNumberOfCalls, minimumNumberOfCalls, Duration.ofMinutes(1)),
                socks5Enabled,
                socks5Host,
                socks5Port);
        return new PaymentCoreAdapter(properties, new InMemoryClientRegistrationRepository(registration));
    }

    /** A local port with nothing listening on it. */
    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static InitiationRequest request(PaymentType type, String reference) {
        return new InitiationRequest(
                UUID.randomUUID(),
                type,
                type == PaymentType.PAYIN ? "BANK_PAYIN" : "BANK_PAYOUT",
                "provider-1",
                "account-1",
                "owl-user-1",
                EUR_123,
                reference);
    }

    private static CancellationRequest cancellation(String paymentCoreId) {
        return new CancellationRequest(paymentCoreId, "provider-1", "account-1", EUR_123);
    }

    private static ConfirmationRequest confirmation(String paymentCoreId, String otp) {
        return new ConfirmationRequest(paymentCoreId, "provider-1", "account-1", EUR_123, otp);
    }

    private void assertNothingSensitiveLogged() {
        assertThat(logs.allText())
                .doesNotContain(
                        "account-1",
                        "provider-1",
                        "owl-user-1",
                        "123.45",
                        "secret-account",
                        "DE00SECRET",
                        PaymentCoreWireMockSupport.ACCESS_TOKEN);
    }
}
