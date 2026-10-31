package com.nox.integration.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nox.domain.payment.PaymentType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Stubs payment-core and its OAuth2 token endpoint, following contracts/payment-core.md. Payment
 * stubs match only a request with NOX's bearer token, so a request without it gets WireMock's 404.
 * Initiation stubs are keyed by {@code referenceNumber} and confirm/cancel stubs by the payment-core
 * id in the path, so tests sharing the server do not interfere. Placeholder credentials only.
 */
public class PaymentCoreWireMockSupport {

    public static final String ACCESS_TOKEN = "test-payment-core-token";
    public static final String BASE_PATH = "/internal-payments/as-provider";
    public static final String CREATED_AT = "2026-10-31T10:00:00Z";
    public static final String EXPIRES_AT = "2026-10-31T10:10:00Z";
    public static final String CONFIRMED_AT = "2026-10-31T10:05:00Z";

    private final WireMock paymentCore;
    private final String tokenPath;

    public PaymentCoreWireMockSupport(WireMock paymentCore, String tokenPath) {
        this.paymentCore = paymentCore;
        this.tokenPath = tokenPath;
    }

    /** A {@link WireMock} client for the shared container. */
    public static PaymentCoreWireMockSupport forContainer() {
        PaymentCoreWireMockSupport support = new PaymentCoreWireMockSupport(
                new WireMock(InfrastructureContainers.OWL.getHost(), InfrastructureContainers.owlPort()),
                InfrastructureContainers.PAYMENT_CORE_TOKEN_PATH);
        support.stubTokenEndpoint();
        return support;
    }

    public static String uniqueReference() {
        return "REF-" + UUID.randomUUID();
    }

    public static String uniquePaymentCoreId() {
        return "pc-" + UUID.randomUUID();
    }

    public static String initiationPath(PaymentType type) {
        return BASE_PATH + (type == PaymentType.PAYIN ? "/pay-in" : "/pay-out");
    }

    public void stubTokenEndpoint() {
        paymentCore.register(post(urlPathEqualTo(tokenPath)).willReturn(okJson("""
                {"access_token":"%s","token_type":"Bearer","expires_in":300}""".formatted(ACCESS_TOKEN))));
    }

    // Initiation

    /** Payment-core creates the payment and echoes {@code amount}/{@code currency}, awaiting the OTP. */
    public void initiationAccepted(
            PaymentType type, String reference, String paymentCoreId, String amount, String currency) {
        initiationAnswers(type, reference, paymentCoreId, "INSERTED", amount, currency);
    }

    public void initiationAnswers(
            PaymentType type, String reference, String paymentCoreId, String status, String amount, String currency) {
        paymentCore.register(initiation(type, reference)
                .willReturn(okJson(payment(paymentCoreId, status, amount, currency, null))));
    }

    public void initiationStatus(PaymentType type, String reference, int status) {
        paymentCore.register(initiation(type, reference).willReturn(problem(status)));
    }

    public void initiationSlow(PaymentType type, String reference, Duration delay) {
        paymentCore.register(initiation(type, reference)
                .willReturn(okJson(payment(uniquePaymentCoreId(), "INSERTED", "1", "EUR", null))
                        .withFixedDelay((int) delay.toMillis())));
    }

    /** The first call fails with 503; the retry is accepted. */
    public void initiationFailsOnceThenAccepted(
            PaymentType type, String reference, String paymentCoreId, String amount, String currency) {
        String scenario = "initiation-" + reference;
        paymentCore.register(initiation(type, reference)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(problem(503))
                .willSetStateTo("recovered"));
        paymentCore.register(initiation(type, reference)
                .inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(payment(paymentCoreId, "INSERTED", amount, currency, null))));
    }

    public List<LoggedRequest> initiations(PaymentType type, String reference) {
        return paymentCore.find(initiationRequest(type, reference));
    }

    public RequestPatternBuilder initiationRequest(PaymentType type, String reference) {
        return postRequestedFor(urlPathEqualTo(initiationPath(type)))
                .withRequestBody(matchingJsonPath("$.referenceNumber", equalTo(reference)));
    }

    // Confirmation and cancellation

    public void confirmationAnswers(String paymentCoreId, String status, String amount, String currency) {
        paymentCore.register(action(paymentCoreId, "confirm")
                .willReturn(okJson(payment(paymentCoreId, status, amount, currency, CONFIRMED_AT))));
    }

    /** Refuses {@code code} with {@code status}, ahead of any other confirmation stub for the payment. */
    public void confirmationRejectsCode(String paymentCoreId, String code, int status) {
        paymentCore.register(action(paymentCoreId, "confirm")
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.otp_code", equalTo(code)))
                .willReturn(problem(status)));
    }

    public void confirmationStatus(String paymentCoreId, int status) {
        paymentCore.register(action(paymentCoreId, "confirm").willReturn(problem(status)));
    }

    public void confirmationSlow(String paymentCoreId, Duration delay) {
        paymentCore.register(action(paymentCoreId, "confirm")
                .willReturn(okJson(payment(paymentCoreId, "COMPLETED", "1", "EUR", CONFIRMED_AT))
                        .withFixedDelay((int) delay.toMillis())));
    }

    /** Answers {@code status} after {@code delay}, so a concurrent request can arrive meanwhile. */
    public void confirmationDelayed(
            String paymentCoreId, String status, String amount, String currency, Duration delay) {
        paymentCore.register(action(paymentCoreId, "confirm")
                .willReturn(okJson(payment(paymentCoreId, status, amount, currency, CONFIRMED_AT))
                        .withFixedDelay((int) delay.toMillis())));
    }

    public void cancellationAnswers(String paymentCoreId, String status, String amount, String currency) {
        paymentCore.register(action(paymentCoreId, "cancel")
                .willReturn(okJson(payment(paymentCoreId, status, amount, currency, null))));
    }

    public void cancellationStatus(String paymentCoreId, int status) {
        paymentCore.register(action(paymentCoreId, "cancel").willReturn(problem(status)));
    }

    public void cancellationSlow(String paymentCoreId, Duration delay) {
        paymentCore.register(action(paymentCoreId, "cancel")
                .willReturn(okJson(payment(paymentCoreId, "CANCELLED", "1", "EUR", null))
                        .withFixedDelay((int) delay.toMillis())));
    }

    public List<LoggedRequest> confirmations(String paymentCoreId) {
        return paymentCore.find(actionRequest(paymentCoreId, "confirm"));
    }

    public List<LoggedRequest> cancellations(String paymentCoreId) {
        return paymentCore.find(actionRequest(paymentCoreId, "cancel"));
    }

    private MappingBuilder initiation(PaymentType type, String reference) {
        return post(urlPathEqualTo(initiationPath(type)))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withRequestBody(matchingJsonPath("$.referenceNumber", equalTo(reference)));
    }

    private MappingBuilder action(String paymentCoreId, String action) {
        return post(urlPathEqualTo(actionPath(paymentCoreId, action)))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN));
    }

    private static RequestPatternBuilder actionRequest(String paymentCoreId, String action) {
        return postRequestedFor(urlPathEqualTo(actionPath(paymentCoreId, action)));
    }

    private static String actionPath(String paymentCoreId, String action) {
        return BASE_PATH + "/" + paymentCoreId + "/" + action;
    }

    private static ResponseDefinitionBuilder okJson(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static ResponseDefinitionBuilder problem(int status) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"success":false,"status":%d}""".formatted(status));
    }

    /** The payment-core envelope, including fields NOX must ignore and never expose. */
    private static String payment(String paymentCoreId, String status, String amount, String currency, String confirmedAt) {
        return """
                {"data":{"payment_id":"%s","account_id":"secret-account","status":"%s",
                  "requestedPaymentMethod":"ANY","paymentMethod":"ANY",
                  "money":{"amount":%s,"currencyCode":"%s"},"referenceNumber":"ignored",
                  "fee":{"amount":1.5,"currencyCode":"%s"},"created_at":"%s","expires_at":"%s",
                  "confirmed_at":%s,"counterparty_account_number":"DE00SECRET"},
                 "meta":{"request_id":"1","timestamp":"%s","path":"/"}}
                """.formatted(
                        paymentCoreId,
                        status,
                        amount,
                        currency,
                        currency,
                        CREATED_AT,
                        EXPIRES_AT,
                        confirmedAt == null ? "null" : "\"" + confirmedAt + "\"",
                        CREATED_AT);
    }
}
