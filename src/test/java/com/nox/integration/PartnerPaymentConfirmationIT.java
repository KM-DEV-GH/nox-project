package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.PartnerPaymentIntegrationSupport;
import com.nox.integration.support.PaymentApi;
import com.nox.integration.support.PaymentCoreWireMockSupport;
import com.nox.integration.support.PaymentFixtures.EstablishedLink;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T026, US3: OTP confirmation over HTTP by userLinkId and externalLinkId (spec FR-010–FR-016). */
class PartnerPaymentConfirmationIT extends PartnerPaymentIntegrationSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void confirmsAPayinByUserLinkIdWithTheAccountCapturedAtInitiation() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "123.45");
        fixtures.activeAccount(partner, PROVIDER_USER_ID, "account-rotated");
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "COMPLETED", "123.45", "EUR");

        EntityExchangeResult<String> result = api.confirm(partner, payments, payment.paymentCoreId(), "123456");

        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode body = PaymentApi.json(result);
        ContractAssertions.assertMatchesSchema(body, "PaymentEnvelope");
        assertThat(body.at("/data/status").asString()).isEqualTo("CONFIRMED");
        assertThat(body.at("/data/confirmedDate").asString()).isEqualTo(PaymentCoreWireMockSupport.CONFIRMED_AT);
        assertThat(body.at("/data/paymentId").asString()).isEqualTo(payment.paymentCoreId());
        assertThat(result.getResponseBody()).doesNotContain("123456", ACCOUNT_ID);
        JsonNode upstream = JSON.readTree(
                paymentCore.confirmations(payment.paymentCoreId()).getFirst().getBodyAsString());
        assertThat(upstream.at("/provider/accountId").asString()).isEqualTo(ACCOUNT_ID);
        assertThat(upstream.at("/otp_code").asString()).isEqualTo("123456");
        assertThat(upstream.at("/money/amount").decimalValue()).isEqualByComparingTo("123.45");
    }

    @Test
    void confirmsAPayoutByExternalLinkId() {
        String payments = PaymentApi.byExternalId(link.externalId());
        Payment payment = awaitingOtp(PaymentType.PAYOUT, payments, "75");
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "COMPLETED", "75", "EUR");

        EntityExchangeResult<String> result = api.confirm(partner, payments, payment.paymentCoreId(), "123456");

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(PaymentApi.json(result).at("/data/type").asString()).isEqualTo("PAYOUT");
        assertThat(PaymentApi.json(result).at("/data/status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void anIncorrectOtpIsRejectedAndTheCorrectOneStillConfirms() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        paymentCore.confirmationRejectsCode(payment.paymentCoreId(), "000000", 422);
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "COMPLETED", "50", "EUR");
        String path = PaymentApi.confirmation(payments, payment.paymentCoreId());

        assertProblem(
                api.confirm(partner, payments, payment.paymentCoreId(), "000000"), 422, "payment_otp_rejected", path);
        assertThat(this.payments
                        .findByPaymentCoreId(payment.paymentCoreId())
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.AWAITING_OTP);

        EntityExchangeResult<String> retried = api.confirm(partner, payments, payment.paymentCoreId(), "123456");
        assertThat(PaymentApi.json(retried).at("/data/status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void anExpiredOtpIsNotReportedAsSuccess() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "EXPIRED", "50", "EUR");

        EntityExchangeResult<String> result = api.confirm(partner, payments, payment.paymentCoreId(), "123456");

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(PaymentApi.json(result).at("/data/status").asString()).isEqualTo("EXPIRED");
        String path = PaymentApi.confirmation(payments, payment.paymentCoreId());
        assertProblem(
                api.confirm(partner, payments, payment.paymentCoreId(), "123456"), 409, "payment_not_actionable", path);
    }

    @Test
    void aRepeatedConfirmationReturnsTheOutcomeWithOneUpstreamCall() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "COMPLETED", "50", "EUR");

        api.confirm(partner, payments, payment.paymentCoreId(), "123456");
        EntityExchangeResult<String> again = api.confirm(partner, payments, payment.paymentCoreId(), "123456");

        assertThat(again.getStatus().value()).isEqualTo(200);
        assertThat(PaymentApi.json(again).at("/data/status").asString()).isEqualTo("CONFIRMED");
        assertThat(paymentCore.confirmations(payment.paymentCoreId())).hasSize(1);
    }

    @Test
    void concurrentConfirmationsReachPaymentCoreOnce() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        paymentCore.confirmationDelayed(payment.paymentCoreId(), "COMPLETED", "50", "EUR", Duration.ofMillis(500));

        List<Integer> statuses = List.of(
                        CompletableFuture.supplyAsync(
                                () -> api.confirm(partner, payments, payment.paymentCoreId(), "1")),
                        CompletableFuture.supplyAsync(
                                () -> api.confirm(partner, payments, payment.paymentCoreId(), "1")))
                .stream()
                .map(CompletableFuture::join)
                .map(result -> result.getStatus().value())
                .toList();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(paymentCore.confirmations(payment.paymentCoreId())).hasSize(1);
        assertThat(this.payments
                        .findByPaymentCoreId(payment.paymentCoreId())
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void aPaymentOfAnotherLinkOrPartnerIsNotFound() {
        Payment payment = awaitingOtp(PaymentType.PAYIN, PaymentApi.byLinkId(link.linkId()), "50");
        EstablishedLink otherLink = fixtures.connectedLink(partner);
        String wrongLink = PaymentApi.byLinkId(otherLink.linkId());

        assertProblem(
                api.confirm(partner, wrongLink, payment.paymentCoreId(), "1"),
                404,
                "not_found",
                PaymentApi.confirmation(wrongLink, payment.paymentCoreId()));
        String foreign = PaymentApi.byLinkId(link.linkId());
        assertProblem(
                api.confirm(partners.active(), foreign, payment.paymentCoreId(), "1"),
                404,
                "not_found",
                PaymentApi.confirmation(foreign, payment.paymentCoreId()));
        String byExternal = PaymentApi.byExternalId(otherLink.externalId());
        assertProblem(
                api.confirm(partner, byExternal, payment.paymentCoreId(), "1"),
                404,
                "not_found",
                PaymentApi.confirmation(byExternal, payment.paymentCoreId()));
        assertThat(paymentCore.confirmations(payment.paymentCoreId())).isEmpty();
    }

    @Test
    void aRevokedLinkCannotConfirm() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        jdbc.sql("update partner_link set status = 'REVOKED' where id = :id")
                .param("id", link.linkId())
                .update();

        assertProblem(
                api.confirm(partner, payments, payment.paymentCoreId(), "1"),
                409,
                "link_not_actionable",
                PaymentApi.confirmation(payments, payment.paymentCoreId()));
    }

    @Test
    void aTimeoutLeavesTheConfirmationUnresolved() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        paymentCore.confirmationSlow(payment.paymentCoreId(), Duration.ofSeconds(3));
        String path = PaymentApi.confirmation(payments, payment.paymentCoreId());

        long started = System.nanoTime();
        assertProblem(
                api.confirm(partner, payments, payment.paymentCoreId(), "123456"), 503, "payment_unresolved", path);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        assertThat(this.payments
                        .findByPaymentCoreId(payment.paymentCoreId())
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.CONFIRMATION_UNKNOWN);
        int attempts = paymentCore.confirmations(payment.paymentCoreId()).size();
        assertProblem(
                api.confirm(partner, payments, payment.paymentCoreId(), "123456"), 503, "payment_unresolved", path);
        assertThat(paymentCore.confirmations(payment.paymentCoreId())).hasSize(attempts);
    }

    @Test
    void malformedOrUnauthorisedConfirmationsAreRefused() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "50");
        String path = PaymentApi.confirmation(payments, payment.paymentCoreId());

        assertProblem(api.post(TOKENS.bff(), partner.clientId(), path, null, "{}"), 400, "bad_request", path);
        assertProblem(api.post(TOKENS.bff(), partner.clientId(), path, null, """
                        {"code":"1","accountId":"other"}"""), 400, "bad_request", path);
        assertProblem(api.post(null, partner.clientId(), path, null, "{\"code\":\"1\"}"), 401, "unauthenticated", path);
        assertProblem(
                api.post(TOKENS.internalBff(), partner.clientId(), path, null, "{\"code\":\"1\"}"),
                403,
                "forbidden",
                path);
        assertThat(paymentCore.confirmations(payment.paymentCoreId())).isEmpty();
    }
}
