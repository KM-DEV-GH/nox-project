package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nox.domain.partner.Partner;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.InfrastructureContainers;
import com.nox.integration.support.PartnerPaymentIntegrationSupport;
import com.nox.integration.support.PaymentApi;
import com.nox.integration.support.PaymentCoreWireMockSupport;
import com.nox.integration.support.PaymentFixtures.EstablishedLink;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T014, US1: PAYIN initiation over HTTP by userLinkId and externalLinkId (spec FR-001–FR-009, FR-016–FR-017). */
class PartnerPaymentPayinIT extends PartnerPaymentIntegrationSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void initiatesAPayinByUserLinkId() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.initiationAccepted(PaymentType.PAYIN, reference, paymentCoreId, "123.45", "EUR");

        EntityExchangeResult<String> result = api.initiate(
                partner,
                PaymentApi.byLinkId(link.linkId()),
                "key-1",
                PaymentApi.body("PAYIN", "123.45", "EUR", reference));

        assertThat(result.getStatus().value()).isEqualTo(201);
        JsonNode body = PaymentApi.json(result);
        ContractAssertions.assertMatchesSchema(body, "PaymentEnvelope");
        JsonNode data = body.get("data");
        assertThat(data.get("paymentId").asString()).isEqualTo(paymentCoreId);
        assertThat(data.get("userLinkId").asString()).isEqualTo(link.linkId().toString());
        assertThat(data.get("externalId").asString()).isEqualTo("tx-" + reference);
        assertThat(data.get("type").asString()).isEqualTo("PAYIN");
        assertThat(data.at("/money/amount").decimalValue()).isEqualByComparingTo("123.45");
        assertThat(data.at("/money/currencyCode").asString()).isEqualTo("EUR");
        assertThat(data.at("/fee/amount").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(data.get("transactionReference").asString()).isEqualTo(reference);
        assertThat(data.get("status").asString()).isEqualTo("AWAITING_OTP");
        assertThat(data.get("creationDate").asString()).isEqualTo(PaymentCoreWireMockSupport.CREATED_AT);
        assertThat(data.get("expirationDate").asString()).isEqualTo(PaymentCoreWireMockSupport.EXPIRES_AT);
        assertThat(data.get("confirmedDate").isNull()).isTrue();
        assertThat(body.at("/meta/instance").asString()).isEqualTo(PaymentApi.byLinkId(link.linkId()));
        assertThat(result.getResponseBody()).doesNotContain("DE00SECRET", "secret-account", ACCOUNT_ID);

        Payment stored = payments.findByPaymentCoreId(paymentCoreId).orElseThrow();
        assertThat(stored.accountIdSnapshot()).isEqualTo(ACCOUNT_ID);
        assertThat(stored.providerUserIdSnapshot()).isEqualTo(PROVIDER_USER_ID);
        LoggedRequest sent =
                paymentCore.initiations(PaymentType.PAYIN, reference).getFirst();
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + PaymentCoreWireMockSupport.ACCESS_TOKEN);
        assertThat(sent.containsHeader("api-key")).isFalse();
        assertThat(sent.getHeader("Idempotency-Key")).isEqualTo(stored.id().toString());
        JsonNode upstream = JSON.readTree(sent.getBodyAsString());
        assertThat(upstream.get("paymentMethod").asString()).isEqualTo(InfrastructureContainers.PAYIN_METHOD);
        assertThat(upstream.at("/client/userId").asString()).isEqualTo(link.userId());
        assertThat(upstream.at("/provider/accountId").asString()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void initiatesAPayinByExternalLinkIdOnTheMostRecentEstablishedLink() {
        EstablishedLink newer = fixtures.link(
                partner, "CONNECTED", link.externalId(), Instant.now().plusSeconds(5));
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(
                PaymentType.PAYIN, reference, PaymentCoreWireMockSupport.uniquePaymentCoreId(), "50", "EUR");

        EntityExchangeResult<String> result = api.initiate(
                partner,
                PaymentApi.byExternalId(link.externalId()),
                "key-1",
                PaymentApi.body("PAYIN", "50", "EUR", reference));

        assertThat(result.getStatus().value()).isEqualTo(201);
        assertThat(PaymentApi.json(result).at("/data/userLinkId").asString())
                .isEqualTo(newer.linkId().toString());
        assertThat(paymentCore
                        .initiations(PaymentType.PAYIN, reference)
                        .getFirst()
                        .getBodyAsString())
                .contains(newer.userId());
    }

    @Test
    void anIdenticalRetryReturnsTheSamePaymentWithOneUpstreamCall() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(
                PaymentType.PAYIN, reference, PaymentCoreWireMockSupport.uniquePaymentCoreId(), "100", "EUR");
        String body = PaymentApi.body("PAYIN", "100", "EUR", reference);

        EntityExchangeResult<String> first = api.initiate(partner, PaymentApi.byLinkId(link.linkId()), "key-1", body);
        EntityExchangeResult<String> retry = api.initiate(partner, PaymentApi.byLinkId(link.linkId()), "key-1", body);

        assertThat(first.getStatus().value()).isEqualTo(201);
        assertThat(retry.getStatus().value()).isEqualTo(200);
        ContractAssertions.assertMatchesSchema(PaymentApi.json(retry), "PaymentEnvelope");
        assertThat(PaymentApi.paymentIdOf(retry)).isEqualTo(PaymentApi.paymentIdOf(first));
        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).hasSize(1);
        assertThat(paymentRows(partner)).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithChangedDetailsIsAConflict() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(
                PaymentType.PAYIN, reference, PaymentCoreWireMockSupport.uniquePaymentCoreId(), "100", "EUR");
        String path = PaymentApi.byLinkId(link.linkId());
        api.initiate(partner, path, "key-1", PaymentApi.body("PAYIN", "100", "EUR", reference));

        assertProblem(
                api.initiate(partner, path, "key-1", PaymentApi.body("PAYIN", "101", "EUR", reference)),
                409,
                "idempotency_conflict",
                path);
        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).hasSize(1);
    }

    /** FR-022: a new key cannot start a second payment for the partner's external ID. */
    @Test
    void reusingAnExternalIdWithAnotherKeyIsAConflict() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationAccepted(
                PaymentType.PAYIN, reference, PaymentCoreWireMockSupport.uniquePaymentCoreId(), "100", "EUR");
        String path = PaymentApi.byLinkId(link.linkId());
        String body = PaymentApi.body("PAYIN", "100", "EUR", reference);

        assertThat(api.initiate(partner, path, "key-1", body).getStatus().value())
                .isEqualTo(201);
        assertThat(api.initiate(partner, path, "key-1", body).getStatus().value())
                .isEqualTo(200);
        assertProblem(api.initiate(partner, path, "key-2", body), 409, "external_id_conflict", path);
        String byExternal = PaymentApi.byExternalId(link.externalId());
        assertProblem(api.initiate(partner, byExternal, "key-3", body), 409, "external_id_conflict", byExternal);

        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).hasSize(1);
        assertThat(paymentRows(partner)).isEqualTo(1);
    }

    @Test
    void aDisabledCurrencyOrAnAmountOutOfRangeIsRefusedBeforePaymentCore() {
        fixtures.limit(partner, PaymentType.PAYIN, "GBP", false, "1", "1000");
        String path = PaymentApi.byLinkId(link.linkId());

        for (String body : new String[] {
            PaymentApi.body("PAYIN", "50", "USD", "REF-USD-" + UUID.randomUUID()),
            PaymentApi.body("PAYIN", "50", "GBP", "REF-GBP-" + UUID.randomUUID()),
            PaymentApi.body("PAYIN", "0.99", "EUR", "REF-LOW-" + UUID.randomUUID()),
            PaymentApi.body("PAYIN", "1000.01", "EUR", "REF-HIGH-" + UUID.randomUUID())
        }) {
            assertProblem(
                    api.initiate(partner, path, UUID.randomUUID().toString(), body),
                    422,
                    "payment_limit_exceeded",
                    path);
        }
        assertThat(paymentRows(partner)).isZero();
    }

    @Test
    void aPartnerWithoutAnActiveAccountIsNotConfigured() {
        Partner bare = partners.active();
        fixtures.standardLimits(bare);
        EstablishedLink bareLink = fixtures.connectedLink(bare);
        String path = PaymentApi.byLinkId(bareLink.linkId());
        String reference = PaymentCoreWireMockSupport.uniqueReference();

        assertProblem(
                api.initiate(bare, path, "key-1", PaymentApi.body("PAYIN", "50", "EUR", reference)),
                422,
                "payment_not_configured",
                path);
        assertThat(paymentCore.initiations(PaymentType.PAYIN, reference)).isEmpty();
    }

    @Test
    void linksThePartnerCannotUseAreNotFoundOrNotActionable() {
        Partner other = partners.active();
        EstablishedLink foreign = fixtures.connectedLink(other);
        EstablishedLink pending = fixtures.link(partner, "PENDING", "external-" + UUID.randomUUID(), Instant.now());
        EstablishedLink revoked = fixtures.link(partner, "REVOKED", "external-" + UUID.randomUUID(), Instant.now());
        String body = PaymentApi.body("PAYIN", "50", "EUR", PaymentCoreWireMockSupport.uniqueReference());

        for (String path : new String[] {
            PaymentApi.byLinkId(foreign.linkId()),
            PaymentApi.byLinkId(pending.linkId()),
            PaymentApi.byLinkId(UUID.randomUUID()),
            PaymentApi.byExternalId(foreign.externalId()),
            PaymentApi.byExternalId("unknown-" + UUID.randomUUID())
        }) {
            assertProblem(api.initiate(partner, path, UUID.randomUUID().toString(), body), 404, "not_found", path);
        }
        String revokedPath = PaymentApi.byExternalId(revoked.externalId());
        assertProblem(api.initiate(partner, revokedPath, "key-r", body), 409, "link_not_actionable", revokedPath);
        assertThat(paymentRows(partner)).isZero();
    }

    @Test
    void onlyThePartnerBffMayInitiate() {
        String path = PaymentApi.byLinkId(link.linkId());
        String body = PaymentApi.body("PAYIN", "50", "EUR", PaymentCoreWireMockSupport.uniqueReference());

        assertProblem(api.post(null, partner.clientId(), path, "key-1", body), 401, "unauthenticated", path);
        assertProblem(api.post(TOKENS.user("alice"), partner.clientId(), path, "key-1", body), 403, "forbidden", path);
        assertProblem(api.post(TOKENS.internalBff(), partner.clientId(), path, "key-1", body), 403, "forbidden", path);
        assertProblem(api.post(TOKENS.bff(), null, path, "key-1", body), 403, "forbidden", path);
        assertProblem(
                api.post(TOKENS.bff(), partners.suspended().clientId(), path, "key-1", body), 403, "forbidden", path);
        assertThat(paymentRows(partner)).isZero();
    }

    @Test
    void malformedRequestsAreBadRequests() {
        String path = PaymentApi.byLinkId(link.linkId());
        String reference = PaymentCoreWireMockSupport.uniqueReference();

        assertProblem(
                api.initiate(partner, path, null, PaymentApi.body("PAYIN", "50", "EUR", reference)),
                400,
                "bad_request",
                path);
        assertProblem(
                api.initiate(partner, path, "k", PaymentApi.body("REFUND", "50", "EUR", reference)),
                400,
                "bad_request",
                path);
        assertProblem(
                api.initiate(partner, path, "k", PaymentApi.body("PAYIN", "-5", "EUR", reference)),
                400,
                "bad_request",
                path);
        assertProblem(api.initiate(partner, path, "k", """
                {"type":"PAYIN","money":{"amount":50,"currencyCode":"EUR"},"externalId":"x",
                 "transactionReference":"%s","accountId":"someone-elses"}""".formatted(reference)), 400, "bad_request", path);
        assertProblem(api.initiate(partner, path, "k", """
                {"type":"PAYIN","money":{"amount":50,"currencyCode":"EUR"},"externalId":"x"}"""), 400, "bad_request", path);
        assertThat(paymentRows(partner)).isZero();
    }

    @Test
    void aRejectionByPaymentCoreIsReported() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationStatus(PaymentType.PAYIN, reference, 422);
        String path = PaymentApi.byLinkId(link.linkId());

        assertProblem(
                api.initiate(partner, path, "key-1", PaymentApi.body("PAYIN", "50", "EUR", reference)),
                422,
                "payment_rejected",
                path);
        assertThat(payments.findByPartnerAndIdempotencyKey(partner.id(), "key-1")
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void aTimeoutIsUnresolvedWithinABoundedTime() {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        paymentCore.initiationSlow(PaymentType.PAYIN, reference, Duration.ofSeconds(3));
        String path = PaymentApi.byLinkId(link.linkId());

        long started = System.nanoTime();
        EntityExchangeResult<String> result =
                api.initiate(partner, path, "key-1", PaymentApi.body("PAYIN", "50", "EUR", reference));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        assertProblem(result, 503, "payment_unresolved", path);
        assertThat(payments.findByPartnerAndIdempotencyKey(partner.id(), "key-1")
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.INITIATION_UNKNOWN);
    }
}
