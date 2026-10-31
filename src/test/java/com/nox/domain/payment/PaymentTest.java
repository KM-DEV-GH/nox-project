package com.nox.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T004: money, limits and the payment lifecycle (data-model.md, Payment State Transitions). */
class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(30);
    private static final Money EUR_100 = Money.of("100.00", "EUR");
    private static final Money FEE = Money.of("1.20", "EUR");
    private static final PartnerPaymentAccount ACCOUNT = new PartnerPaymentAccount("provider-1", "account-1");

    @Test
    void moneyRequiresAnAmountAndACurrency() {
        assertThatThrownBy(() -> new Money(null, "EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("-0.01", "EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.of("0", "EUR").isPositive()).isFalse();
        assertThat(EUR_100.isPositive()).isTrue();
    }

    @Test
    void moneyComparesAmountsByValueNotScale() {
        assertThat(Money.of("100", "EUR").sameAs(EUR_100)).isTrue();
        assertThat(Money.of("100.01", "EUR").sameAs(EUR_100)).isFalse();
        assertThat(Money.of("100.00", "USD").sameAs(EUR_100)).isFalse();
    }

    @Test
    void aLimitAllowsOnlyItsEnabledCurrencyWithinInclusiveBounds() {
        PaymentLimit limit = new PaymentLimit(PaymentType.PAYIN, "EUR", true, new BigDecimal("10"), new BigDecimal("100"));

        assertThat(limit.allows(Money.of("10", "EUR"))).isTrue();
        assertThat(limit.allows(Money.of("100.00", "EUR"))).isTrue();
        assertThat(limit.allows(Money.of("9.99", "EUR"))).isFalse();
        assertThat(limit.allows(Money.of("100.01", "EUR"))).isFalse();
        assertThat(limit.allows(Money.of("50", "USD"))).isFalse();
        assertThat(new PaymentLimit(PaymentType.PAYIN, "EUR", false, BigDecimal.ONE, BigDecimal.TEN)
                        .allows(Money.of("5", "EUR")))
                .isFalse();
    }

    @Test
    void aLimitNeedsAPositiveMinimumNoGreaterThanItsMaximum() {
        assertThatThrownBy(() -> new PaymentLimit(PaymentType.PAYIN, "EUR", true, BigDecimal.ZERO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentLimit(PaymentType.PAYIN, "EUR", true, BigDecimal.TEN, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStatusSetIsExactlyTheDataModels() {
        assertThat(PaymentStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "INITIATING",
                        "AWAITING_OTP",
                        "CONFIRMING",
                        "CANCELLING",
                        "CONFIRMED",
                        "CANCELLED",
                        "FAILED",
                        "EXPIRED",
                        "INITIATION_UNKNOWN",
                        "CONFIRMATION_UNKNOWN",
                        "CANCELLATION_UNKNOWN");
    }

    @Test
    void aNewPaymentIsInitiatingWithItsAccountSnapshot() {
        Payment payment = newPayment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATING);
        assertThat(payment.providerUserIdSnapshot()).isEqualTo("provider-1");
        assertThat(payment.accountIdSnapshot()).isEqualTo("account-1");
        assertThat(payment.paymentCoreId()).isNull();
        assertThat(payment.version()).isNull();
        assertThat(payment.createdAt()).isEqualTo(NOW);
    }

    @Test
    void aPaymentNeedsAPositiveAmount() {
        assertThatThrownBy(() -> Payment.initiate(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ACCOUNT,
                        "key-1",
                        "fingerprint",
                        PaymentType.PAYIN,
                        "BANK",
                        Money.of("0", "EUR"),
                        "external-1",
                        "reference-1",
                        null,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAcceptedInitiationAwaitsTheOtp() {
        Payment payment = newPayment();

        payment.recordInitiation(processor(PaymentStatus.AWAITING_OTP, EUR_100), LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.AWAITING_OTP);
        assertThat(payment.paymentCoreId()).isEqualTo("pc-1");
        assertThat(payment.fee()).isEqualTo(FEE);
        assertThat(payment.paymentCoreCreatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(payment.expirationDate()).isEqualTo(NOW.plusSeconds(600));
        assertThat(payment.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void echoedMoneyThatDiffersIsUnresolvedNotSuccess() {
        Payment payment = newPayment();

        payment.recordInitiation(processor(PaymentStatus.AWAITING_OTP, Money.of("99.00", "EUR")), LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATION_UNKNOWN);
        assertThat(payment.paymentCoreId()).isEqualTo("pc-1");
    }

    @Test
    void anUnrecognisedProcessorStatusIsUnresolved() {
        Payment payment = newPayment();

        payment.recordInitiation(processor(null, EUR_100), LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATION_UNKNOWN);
    }

    @Test
    void initiationCanFailOrBeAmbiguous() {
        Payment rejected = newPayment();
        rejected.rejectInitiation(LATER);
        Payment ambiguous = newPayment();
        ambiguous.initiationUnknown(LATER);

        assertThat(rejected.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(ambiguous.status()).isEqualTo(PaymentStatus.INITIATION_UNKNOWN);
        assertThatThrownBy(() -> rejected.initiationUnknown(LATER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirmationMovesThroughConfirmingToTheProcessorsOutcome() {
        Payment payment = awaitingOtp();

        assertThat(payment.beginConfirmation(LATER)).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMING);
        payment.recordOutcome(confirmed(), LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.confirmedDate()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void cancellationMovesThroughCancellingToCancelled() {
        Payment payment = awaitingOtp();

        assertThat(payment.beginCancellation(LATER)).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLING);
        payment.recordOutcome(processor(PaymentStatus.CANCELLED, EUR_100), LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    /** Only one of confirm and cancel can start: the other finds the payment no longer awaiting the OTP. */
    @Test
    void confirmationAndCancellationAreMutuallyExclusive() {
        Payment confirming = awaitingOtp();
        confirming.beginConfirmation(LATER);
        Payment cancelling = awaitingOtp();
        cancelling.beginCancellation(LATER);

        assertThat(confirming.beginCancellation(LATER)).isFalse();
        assertThat(cancelling.beginConfirmation(LATER)).isFalse();
        assertThat(confirming.status()).isEqualTo(PaymentStatus.CONFIRMING);
        assertThat(cancelling.status()).isEqualTo(PaymentStatus.CANCELLING);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"AWAITING_OTP"},
            mode = EnumSource.Mode.EXCLUDE)
    void onlyAPaymentAwaitingTheOtpCanBeConfirmedOrCancelled(PaymentStatus status) {
        Payment payment = restored(status);

        assertThat(payment.beginConfirmation(LATER)).isFalse();
        assertThat(payment.beginCancellation(LATER)).isFalse();
        assertThat(payment.status()).isEqualTo(status);
    }

    @Test
    void aRejectedConfirmationOrCancellationAwaitsTheOtpAgain() {
        Payment confirming = awaitingOtp();
        confirming.beginConfirmation(LATER);
        Payment cancelling = awaitingOtp();
        cancelling.beginCancellation(LATER);

        confirming.returnToAwaitingOtp(LATER);
        cancelling.returnToAwaitingOtp(LATER);

        assertThat(confirming.status()).isEqualTo(PaymentStatus.AWAITING_OTP);
        assertThat(cancelling.status()).isEqualTo(PaymentStatus.AWAITING_OTP);
    }

    @Test
    void anAmbiguousOutcomeIsKeptForReconciliation() {
        Payment confirming = awaitingOtp();
        confirming.beginConfirmation(LATER);
        Payment cancelling = awaitingOtp();
        cancelling.beginCancellation(LATER);

        confirming.outcomeUnknown(LATER);
        cancelling.outcomeUnknown(LATER);

        assertThat(confirming.status()).isEqualTo(PaymentStatus.CONFIRMATION_UNKNOWN);
        assertThat(cancelling.status()).isEqualTo(PaymentStatus.CANCELLATION_UNKNOWN);
    }

    @Test
    void anUnrecognisedOrMismatchedOutcomeIsAmbiguous() {
        Payment unrecognised = awaitingOtp();
        unrecognised.beginConfirmation(LATER);
        Payment mismatched = awaitingOtp();
        mismatched.beginCancellation(LATER);

        unrecognised.recordOutcome(processor(null, EUR_100), LATER);
        mismatched.recordOutcome(processor(PaymentStatus.CANCELLED, Money.of("1", "EUR")), LATER);

        assertThat(unrecognised.status()).isEqualTo(PaymentStatus.CONFIRMATION_UNKNOWN);
        assertThat(mismatched.status()).isEqualTo(PaymentStatus.CANCELLATION_UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INITIATING", "CONFIRMING", "CANCELLING"})
    void anOutcomeIsRecordedOnlyForAnOperationInFlight(String inFlight) {
        Payment idle = awaitingOtp();

        assertThatThrownBy(() -> idle.recordOutcome(confirmed(), LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> idle.returnToAwaitingOtp(LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> idle.outcomeUnknown(LATER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> idle.recordInitiation(confirmed(), LATER)).isInstanceOf(IllegalStateException.class);
        assertThat(PaymentStatus.valueOf(inFlight).isInFlight()).isTrue();
    }

    private static Payment newPayment() {
        return Payment.initiate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ACCOUNT,
                "key-1",
                "fingerprint",
                PaymentType.PAYIN,
                "BANK",
                EUR_100,
                "external-1",
                "reference-1",
                "a deposit",
                NOW);
    }

    private static Payment awaitingOtp() {
        Payment payment = newPayment();
        payment.recordInitiation(processor(PaymentStatus.AWAITING_OTP, EUR_100), NOW);
        return payment;
    }

    private static Payment restored(PaymentStatus status) {
        return Payment.restore(
                UUID.randomUUID(),
                "pc-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "provider-1",
                "account-1",
                "key-1",
                "fingerprint",
                PaymentType.PAYOUT,
                "BANK",
                EUR_100,
                "external-1",
                "reference-1",
                null,
                FEE,
                status,
                NOW,
                NOW,
                null,
                null,
                NOW,
                3L);
    }

    private static ProcessorPayment processor(PaymentStatus status, Money money) {
        return new ProcessorPayment(
                "pc-1", status, money, FEE, NOW.plusSeconds(1), NOW.plusSeconds(600), null);
    }

    private static ProcessorPayment confirmed() {
        return new ProcessorPayment(
                "pc-1",
                PaymentStatus.CONFIRMED,
                EUR_100,
                FEE,
                NOW.plusSeconds(1),
                NOW.plusSeconds(600),
                NOW.plusSeconds(120));
    }
}
