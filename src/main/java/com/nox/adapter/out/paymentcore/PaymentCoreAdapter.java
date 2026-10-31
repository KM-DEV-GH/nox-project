package com.nox.adapter.out.paymentcore;

import com.nox.adapter.out.http.HttpCallFailure;
import com.nox.adapter.out.http.HttpClients;
import com.nox.adapter.out.http.ResilientCall;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.CancellationBody;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.Client;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.ConfirmationBody;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.Envelope;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.InitiationBody;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.MoneyBody;
import com.nox.adapter.out.paymentcore.PaymentCoreContract.Party;
import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.domain.payment.PaymentType;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls payment-core exactly as contracts/payment-core.md describes, through {@link ResilientCall}:
 * bounded timeouts, a circuit breaker and retries on transient failures. Retrying is safe:
 * initialization carries a stable {@code Idempotency-Key}, and payment-core deduplicates confirm and
 * cancel. A client error is a rejection and is never retried. Neither bodies, URLs nor exceptions are
 * logged: they carry account identifiers, amounts or the OTP.
 */
@Component
public class PaymentCoreAdapter implements PaymentProcessorPort {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentCoreAdapter.class);
    private static final String BASE_PATH = "/internal-payments/as-provider";
    private static final String ACTION_PATH = BASE_PATH + "/{paymentCoreId}/{action}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient paymentCore;
    private final ResilientCall resilient;

    public PaymentCoreAdapter(PaymentCoreProperties properties, ClientRegistrationRepository registrations) {
        this.paymentCore = HttpClients.restClientBuilder(
                        properties.baseUrl(),
                        properties,
                        registrations.findByRegistrationId(properties.registrationId()))
                .build();
        this.resilient = new ResilientCall("payment-core", properties);
    }

    @Override
    public ProcessorResult initiate(InitiationRequest request) {
        InitiationBody body = new InitiationBody(
                new Party(request.providerUserId(), request.accountId()),
                new Client(request.clientUserId()),
                MoneyBody.of(request.money()),
                request.paymentMethod(),
                request.referenceNumber());
        String path = BASE_PATH + (request.type() == PaymentType.PAYIN ? "/pay-in" : "/pay-out");
        return post(
                "initiation",
                headers -> headers.set(IDEMPOTENCY_KEY, request.idempotencyKey().toString()),
                body,
                path);
    }

    @Override
    public ProcessorResult confirm(ConfirmationRequest request) {
        ConfirmationBody body = new ConfirmationBody(
                new Party(request.providerUserId(), request.accountId()), MoneyBody.of(request.money()), request.otp());
        return post("confirmation", headers -> {}, body, ACTION_PATH, request.paymentCoreId(), "confirm");
    }

    @Override
    public ProcessorResult cancel(CancellationRequest request) {
        CancellationBody body = new CancellationBody(
                new Party(request.providerUserId(), request.accountId()), MoneyBody.of(request.money()));
        return post("cancellation", headers -> {}, body, ACTION_PATH, request.paymentCoreId(), "cancel");
    }

    private ProcessorResult post(
            String operation, Consumer<HttpHeaders> headers, Object body, String uriTemplate, Object... uriVariables) {
        try {
            Envelope envelope = resilient.execute(
                    () -> paymentCore
                            .post()
                            .uri(uriTemplate, uriVariables)
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(Envelope.class),
                    Envelope::hasPayment);
            return new ProcessorResult.Completed(envelope.data().toProcessorPayment());
        } catch (HttpCallFailure failure) {
            return switch (failure.kind()) {
                case REJECTED -> {
                    LOG.atWarn().addKeyValue("status", failure.status()).log("payment-core rejected the {}", operation);
                    yield new ProcessorResult.Rejected();
                }
                case NOT_ATTEMPTED -> {
                    LOG.warn("payment-core {} not attempted: circuit open", operation);
                    yield new ProcessorResult.NotAttempted();
                }
                case UNKNOWN -> {
                    LOG.warn("payment-core {} outcome unknown after retries", operation);
                    yield new ProcessorResult.Unknown();
                }
            };
        }
    }
}
