package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentType;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.InfrastructureContainers;
import com.nox.integration.support.PartnerPaymentIntegrationSupport;
import com.nox.integration.support.PaymentApi;
import com.nox.integration.support.PaymentCoreWireMockSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T021, US2: PAYOUT initiation over HTTP by userLinkId and externalLinkId. */
class PartnerPaymentPayoutIT extends PartnerPaymentIntegrationSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void initiatesAPayoutThroughEitherIdentifier() {
        for (String path : new String[] {PaymentApi.byLinkId(link.linkId()), PaymentApi.byExternalId(link.externalId())}) {
            String reference = PaymentCoreWireMockSupport.uniqueReference();
            String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
            paymentCore.initiationAccepted(PaymentType.PAYOUT, reference, paymentCoreId, "75", "EUR");

            EntityExchangeResult<String> result = api.initiate(
                    partner, path, UUID.randomUUID().toString(), PaymentApi.body("PAYOUT", "75", "EUR", reference));

            assertThat(result.getStatus().value()).isEqualTo(201);
            JsonNode body = PaymentApi.json(result);
            ContractAssertions.assertMatchesSchema(body, "PaymentEnvelope");
            assertThat(body.at("/data/type").asString()).isEqualTo("PAYOUT");
            assertThat(body.at("/data/paymentId").asString()).isEqualTo(paymentCoreId);
            assertThat(body.at("/data/userLinkId").asString()).isEqualTo(link.linkId().toString());
            assertThat(body.at("/data/status").asString()).isEqualTo("AWAITING_OTP");
            assertThat(body.at("/data/fee/amount").decimalValue()).isEqualByComparingTo("1.5");
            assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).isEmpty();
            JsonNode upstream = JSON.readTree(paymentCore
                    .initiations(PaymentType.PAYOUT, reference)
                    .getFirst()
                    .getBodyAsString());
            assertThat(upstream.get("paymentMethod").asString()).isEqualTo(InfrastructureContainers.PAYOUT_METHOD);
            Payment stored = payments.findByPaymentCoreId(paymentCoreId).orElseThrow();
            assertThat(stored.paymentMethod()).isEqualTo(InfrastructureContainers.PAYOUT_METHOD);
            assertThat(stored.accountIdSnapshot()).isEqualTo(ACCOUNT_ID);
        }
    }

    @Test
    void payoutLimitsAreSeparateFromPayinLimits() {
        fixtures.limit(partner, PaymentType.PAYIN, "USD", true, "1", "1000");
        fixtures.limit(partner, PaymentType.PAYOUT, "USD", false, "1", "1000");
        String path = PaymentApi.byLinkId(link.linkId());

        for (String body : new String[] {
            PaymentApi.body("PAYOUT", "50", "USD", PaymentCoreWireMockSupport.uniqueReference()),
            PaymentApi.body("PAYOUT", "0.5", "EUR", PaymentCoreWireMockSupport.uniqueReference()),
            PaymentApi.body("PAYOUT", "1000.01", "EUR", PaymentCoreWireMockSupport.uniqueReference())
        }) {
            assertProblem(api.initiate(partner, path, UUID.randomUUID().toString(), body), 422, "payment_limit_exceeded", path);
        }
        assertThat(paymentRows(partner)).isZero();
    }

    @Test
    void aPayoutRetryIsIdempotent() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(PaymentType.PAYOUT, reference, PaymentCoreWireMockSupport.uniquePaymentCoreId(), "75", "EUR");
        String path = PaymentApi.byExternalId(link.externalId());
        String body = PaymentApi.body("PAYOUT", "75", "EUR", reference);

        EntityExchangeResult<String> first = api.initiate(partner, path, "key-1", body);
        EntityExchangeResult<String> retry = api.initiate(partner, path, "key-1", body);

        assertThat(retry.getStatus().value()).isEqualTo(200);
        assertThat(PaymentApi.paymentIdOf(retry)).isEqualTo(PaymentApi.paymentIdOf(first));
        assertThat(paymentCore.initiations(PaymentType.PAYOUT, reference)).hasSize(1);
        assertProblem(
                api.initiate(partner, path, "key-1", PaymentApi.body("PAYIN", "75", "EUR", reference)),
                409,
                "idempotency_conflict",
                path);
    }

    @Test
    void onlyThePartnerBffMayInitiateAPayout() {
        String path = PaymentApi.byExternalId(link.externalId());
        String body = PaymentApi.body("PAYOUT", "75", "EUR", PaymentCoreWireMockSupport.uniqueReference());

        assertProblem(api.post(null, partner.clientId(), path, "key-1", body), 401, "unauthenticated", path);
        assertProblem(api.post(TOKENS.service(), partner.clientId(), path, "key-1", body), 403, "forbidden", path);
        assertProblem(api.initiate(partners.active(), path, "key-1", body), 404, "not_found", path);
        assertThat(paymentRows(partner)).isZero();
    }
}
