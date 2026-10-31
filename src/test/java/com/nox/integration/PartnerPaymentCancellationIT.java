package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.PartnerPaymentIntegrationSupport;
import com.nox.integration.support.PaymentApi;
import com.nox.integration.support.PaymentFixtures.EstablishedLink;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T033, US4: cancellation over HTTP by userLinkId and externalLinkId (spec FR-018–FR-021, SC-007). */
class PartnerPaymentCancellationIT extends PartnerPaymentIntegrationSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void cancelsAPendingPaymentThroughEitherIdentifierWithTheOriginalAccount() {
        for (String payments :
                new String[] {PaymentApi.byLinkId(link.linkId()), PaymentApi.byExternalId(link.externalId())}) {
            Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "60");
            fixtures.activeAccount(partner, PROVIDER_USER_ID, "account-rotated");
            paymentCore.cancellationAnswers(payment.paymentCoreId(), "CANCELLED", "60", "EUR");

            EntityExchangeResult<String> result = api.cancel(partner, payments, payment.paymentCoreId());

            assertThat(result.getStatus().value()).isEqualTo(200);
            JsonNode body = PaymentApi.json(result);
            ContractAssertions.assertMatchesSchema(body, "PaymentEnvelope");
            assertThat(body.at("/data/status").asString()).isEqualTo("CANCELLED");
            JsonNode upstream = JSON.readTree(paymentCore
                    .cancellations(payment.paymentCoreId())
                    .getFirst()
                    .getBodyAsString());
            assertThat(upstream.at("/provider/accountId").asString()).isEqualTo(ACCOUNT_ID);
            assertThat(upstream.has("otp_code")).isFalse();
            fixtures.activeAccount(partner, PROVIDER_USER_ID, ACCOUNT_ID);
        }
    }

    @Test
    void aRepeatedCancellationReturnsTheOutcomeAndTheyCannotConfirmAfterwards() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYOUT, payments, "60");
        paymentCore.cancellationAnswers(payment.paymentCoreId(), "CANCELLED", "60", "EUR");
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "COMPLETED", "60", "EUR");

        api.cancel(partner, payments, payment.paymentCoreId());
        EntityExchangeResult<String> again = api.cancel(partner, payments, payment.paymentCoreId());

        assertThat(again.getStatus().value()).isEqualTo(200);
        assertThat(PaymentApi.json(again).at("/data/status").asString()).isEqualTo("CANCELLED");
        assertThat(paymentCore.cancellations(payment.paymentCoreId())).hasSize(1);
        assertProblem(
                api.confirm(partner, payments, payment.paymentCoreId(), "123456"),
                409,
                "payment_not_actionable",
                PaymentApi.confirmation(payments, payment.paymentCoreId()));
        assertThat(paymentCore.confirmations(payment.paymentCoreId())).isEmpty();
    }

    @Test
    void aConfirmedPaymentCannotBeCancelled() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "60");
        paymentCore.confirmationAnswers(payment.paymentCoreId(), "COMPLETED", "60", "EUR");
        api.confirm(partner, payments, payment.paymentCoreId(), "123456");

        assertProblem(
                api.cancel(partner, payments, payment.paymentCoreId()),
                409,
                "payment_not_actionable",
                PaymentApi.cancellation(payments, payment.paymentCoreId()));
        assertThat(paymentCore.cancellations(payment.paymentCoreId())).isEmpty();
    }

    /** SC-007: when confirm and cancel race, exactly one reaches payment-core and one terminal outcome is stored. */
    @Test
    void confirmationAndCancellationNeverBothSucceed() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "60");
        paymentCore.confirmationDelayed(payment.paymentCoreId(), "COMPLETED", "60", "EUR", Duration.ofMillis(500));
        paymentCore.cancellationAnswers(payment.paymentCoreId(), "CANCELLED", "60", "EUR");

        List<Integer> statuses = List.of(
                        CompletableFuture.supplyAsync(
                                () -> api.confirm(partner, payments, payment.paymentCoreId(), "1")),
                        CompletableFuture.supplyAsync(() -> api.cancel(partner, payments, payment.paymentCoreId())))
                .stream()
                .map(CompletableFuture::join)
                .map(result -> result.getStatus().value())
                .toList();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        int upstreamCalls = paymentCore.confirmations(payment.paymentCoreId()).size()
                + paymentCore.cancellations(payment.paymentCoreId()).size();
        assertThat(upstreamCalls).isEqualTo(1);
        assertThat(this.payments
                        .findByPaymentCoreId(payment.paymentCoreId())
                        .orElseThrow()
                        .status())
                .isIn(PaymentStatus.CONFIRMED, PaymentStatus.CANCELLED);
    }

    @Test
    void aPaymentOfAnotherLinkOrPartnerIsNotCancelled() {
        Payment payment = awaitingOtp(PaymentType.PAYIN, PaymentApi.byLinkId(link.linkId()), "60");
        EstablishedLink otherLink = fixtures.connectedLink(partner);

        for (String payments :
                new String[] {PaymentApi.byLinkId(otherLink.linkId()), PaymentApi.byExternalId(otherLink.externalId())
                }) {
            assertProblem(
                    api.cancel(partner, payments, payment.paymentCoreId()),
                    404,
                    "not_found",
                    PaymentApi.cancellation(payments, payment.paymentCoreId()));
        }
        String own = PaymentApi.byLinkId(link.linkId());
        assertProblem(
                api.cancel(partners.active(), own, payment.paymentCoreId()),
                404,
                "not_found",
                PaymentApi.cancellation(own, payment.paymentCoreId()));
        assertThat(paymentCore.cancellations(payment.paymentCoreId())).isEmpty();
    }

    @Test
    void aTimeoutLeavesTheCancellationUnresolved() {
        String payments = PaymentApi.byExternalId(link.externalId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "60");
        paymentCore.cancellationSlow(payment.paymentCoreId(), Duration.ofSeconds(3));
        String path = PaymentApi.cancellation(payments, payment.paymentCoreId());

        assertProblem(api.cancel(partner, payments, payment.paymentCoreId()), 503, "payment_unresolved", path);

        assertThat(this.payments
                        .findByPaymentCoreId(payment.paymentCoreId())
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.CANCELLATION_UNKNOWN);
        int attempts = paymentCore.cancellations(payment.paymentCoreId()).size();
        assertProblem(api.cancel(partner, payments, payment.paymentCoreId()), 503, "payment_unresolved", path);
        assertThat(paymentCore.cancellations(payment.paymentCoreId())).hasSize(attempts);
    }

    @Test
    void onlyThePartnerBffMayCancel() {
        String payments = PaymentApi.byLinkId(link.linkId());
        Payment payment = awaitingOtp(PaymentType.PAYIN, payments, "60");
        String path = PaymentApi.cancellation(payments, payment.paymentCoreId());

        assertProblem(api.post(null, partner.clientId(), path, null, null), 401, "unauthenticated", path);
        assertProblem(api.post(TOKENS.internalBff(), partner.clientId(), path, null, null), 403, "forbidden", path);
        assertProblem(api.post(TOKENS.user("alice"), partner.clientId(), path, null, null), 403, "forbidden", path);
        assertThat(paymentCore.cancellations(payment.paymentCoreId())).isEmpty();
    }
}
