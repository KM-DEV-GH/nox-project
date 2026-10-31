package com.nox.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.application.fake.InMemoryPartnerLinkRepository;
import com.nox.application.fake.InMemoryPaymentRepository;
import com.nox.application.fake.StubPaymentProcessor;
import com.nox.application.port.in.payment.ConfirmPaymentUseCase.Command;
import com.nox.application.port.in.payment.LinkReference;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.payment.PaymentProcessorPort.ConfirmationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.domain.payment.ProcessorPayment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** T024: US3 OTP confirmation (spec FR-010–FR-014, FR-019). */
class ConfirmPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:02:00Z");
    private static final UUID PARTNER_ID = UUID.randomUUID();
    private static final Money EUR_100 = Money.of("100", "EUR");
    private static final String PAYMENT_CORE_ID = "pc-1";

    private final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final StubPaymentProcessor processor = new StubPaymentProcessor();
    private final ConfirmPaymentService service =
            new ConfirmPaymentService(links, payments, processor, Clock.fixed(NOW, ZoneOffset.UTC));

    private PartnerLink link;

    @BeforeEach
    void paymentAwaitingOtp() {
        link = link(PARTNER_ID, "external-1", PartnerLinkStatus.CONNECTED);
        payments.insert(awaitingOtp(PARTNER_ID, link.id(), PAYMENT_CORE_ID, "key-1"));
    }

    @Test
    void forwardsTheOtpWithTheOriginalAccountAndMoneyAndRecordsTheConfirmation() {
        Payment confirmed = service.confirm(command(byId(), PAYMENT_CORE_ID, "123456"));

        assertThat(confirmed.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(confirmed.confirmedDate()).isEqualTo(StubPaymentProcessor.CONFIRMED_AT);
        assertThat(confirmed.updatedAt()).isEqualTo(NOW);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CONFIRMED);
        ConfirmationRequest sent = processor.confirmations().getFirst();
        assertThat(sent.paymentCoreId()).isEqualTo(PAYMENT_CORE_ID);
        assertThat(sent.providerUserId()).isEqualTo("provider-1");
        assertThat(sent.accountId()).isEqualTo("account-at-initiation");
        assertThat(sent.money()).isEqualTo(EUR_100);
        assertThat(sent.otp()).isEqualTo("123456");
        assertThat(sent.toString()).doesNotContain("123456", "account-at-initiation");
    }

    @Test
    void confirmsThroughTheExternalLinkId() {
        assertThat(service.confirm(command(new LinkReference.ByExternalId("external-1"), PAYMENT_CORE_ID, "123456"))
                        .status())
                .isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void aRepeatedConfirmationReturnsTheOutcomeWithoutASecondCall() {
        service.confirm(command(byId(), PAYMENT_CORE_ID, "123456"));

        Payment again = service.confirm(command(byId(), PAYMENT_CORE_ID, "123456"));

        assertThat(again.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(processor.confirmations()).hasSize(1);
    }

    @Test
    void aPaymentOfAnotherLinkOrPartnerIsNotFound() {
        PartnerLink otherLink = link(PARTNER_ID, "external-2", PartnerLinkStatus.CONNECTED);
        UUID otherPartner = UUID.randomUUID();
        PartnerLink foreignLink = link(otherPartner, "external-1", PartnerLinkStatus.CONNECTED);
        payments.insert(awaitingOtp(otherPartner, foreignLink.id(), "pc-foreign", "key-1"));

        assertFailure(
                command(new LinkReference.ById(otherLink.id()), PAYMENT_CORE_ID, "1"),
                PaymentFailure.PAYMENT_NOT_FOUND);
        assertFailure(command(byId(), "pc-foreign", "1"), PaymentFailure.PAYMENT_NOT_FOUND);
        assertFailure(command(byId(), "pc-unknown", "1"), PaymentFailure.PAYMENT_NOT_FOUND);
        assertFailure(
                new Command(otherPartner, new LinkReference.ById(link.id()), PAYMENT_CORE_ID, "1"),
                PaymentFailure.LINK_NOT_FOUND);
        assertThat(processor.confirmations()).isEmpty();
    }

    @Test
    void aRevokedLinkCannotConfirm() {
        PartnerLink revoked = link(PARTNER_ID, "external-3", PartnerLinkStatus.REVOKED);
        payments.insert(awaitingOtp(PARTNER_ID, revoked.id(), "pc-revoked", "key-2"));

        assertFailure(
                command(new LinkReference.ById(revoked.id()), "pc-revoked", "1"), PaymentFailure.LINK_NOT_CONNECTED);
        assertThat(processor.confirmations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CANCELLED", "FAILED", "EXPIRED"})
    void aFinishedPaymentIsNotConfirmable(PaymentStatus status) {
        payments.insert(stored(PARTNER_ID, link.id(), "pc-done", "key-3", status));

        assertFailure(command(byId(), "pc-done", "1"), PaymentFailure.NOT_ACTIONABLE);
        assertThat(processor.confirmations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CONFIRMING", "CANCELLING"})
    void aPaymentWithAnActionInFlightIsInProgress(PaymentStatus status) {
        payments.insert(stored(PARTNER_ID, link.id(), "pc-busy", "key-3", status));

        assertFailure(command(byId(), "pc-busy", "1"), PaymentFailure.IN_PROGRESS);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CONFIRMATION_UNKNOWN", "CANCELLATION_UNKNOWN"})
    void anUnresolvedPaymentStaysUnresolved(PaymentStatus status) {
        payments.insert(stored(PARTNER_ID, link.id(), "pc-unknown", "key-3", status));

        assertFailure(command(byId(), "pc-unknown", "1"), PaymentFailure.UNRESOLVED);
        assertThat(processor.confirmations()).isEmpty();
    }

    @Test
    void aRejectedOtpLeavesThePaymentAwaitingAnotherAttempt() {
        processor.onConfirm(new ProcessorResult.Rejected());

        assertFailure(command(byId(), PAYMENT_CORE_ID, "000000"), PaymentFailure.OTP_REJECTED);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.AWAITING_OTP);

        processor.onConfirm(request -> StubPaymentProcessor.outcome(PAYMENT_CORE_ID, PaymentStatus.CONFIRMED, EUR_100));
        assertThat(service.confirm(command(byId(), PAYMENT_CORE_ID, "123456")).status())
                .isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void anExpiredOrFailedOutcomeIsReportedNotSuccess() {
        processor.onConfirm(StubPaymentProcessor.outcome(PAYMENT_CORE_ID, PaymentStatus.EXPIRED, EUR_100));

        Payment expired = service.confirm(command(byId(), PAYMENT_CORE_ID, "123456"));

        assertThat(expired.status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(expired.confirmedDate()).isNull();
    }

    @Test
    void anAmbiguousConfirmationIsKeptForReconciliation() {
        processor.onConfirm(new ProcessorResult.Unknown());

        assertFailure(command(byId(), PAYMENT_CORE_ID, "123456"), PaymentFailure.UNRESOLVED);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CONFIRMATION_UNKNOWN);
        assertFailure(command(byId(), PAYMENT_CORE_ID, "123456"), PaymentFailure.UNRESOLVED);
        assertThat(processor.confirmations()).hasSize(1);
    }

    @Test
    void anUnrecognisedOutcomeIsAmbiguous() {
        processor.onConfirm(new ProcessorResult.Completed(
                new ProcessorPayment(PAYMENT_CORE_ID, null, EUR_100, StubPaymentProcessor.FEE, null, null, null)));

        assertFailure(command(byId(), PAYMENT_CORE_ID, "123456"), PaymentFailure.UNRESOLVED);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CONFIRMATION_UNKNOWN);
    }

    @Test
    void anOpenCircuitLeavesThePaymentAwaitingTheOtp() {
        processor.onConfirm(new ProcessorResult.NotAttempted());

        assertFailure(command(byId(), PAYMENT_CORE_ID, "123456"), PaymentFailure.PROCESSOR_UNAVAILABLE);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.AWAITING_OTP);
    }

    /** A request that read the payment before another claimed it loses, without calling payment-core. */
    @Test
    void aConcurrentClaimWinsAndThisRequestIsNotActionable() {
        payments.interleave(() -> {
            Payment other = payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow();
            other.beginCancellation(NOW);
            payments.save(other);
        });

        assertFailure(command(byId(), PAYMENT_CORE_ID, "123456"), PaymentFailure.NOT_ACTIONABLE);
        assertThat(processor.confirmations()).isEmpty();
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CANCELLING);
    }

    private LinkReference byId() {
        return new LinkReference.ById(link.id());
    }

    private static Command command(LinkReference link, String paymentId, String otp) {
        return new Command(PARTNER_ID, link, paymentId, otp);
    }

    private PartnerLink link(UUID partnerId, String externalId, PartnerLinkStatus status) {
        PartnerLink created = PartnerLink.restore(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                partnerId,
                externalId,
                "licensee-1",
                status,
                NOW,
                0L,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600));
        links.put(created);
        return created;
    }

    static Payment awaitingOtp(UUID partnerId, UUID linkId, String paymentCoreId, String key) {
        return stored(partnerId, linkId, paymentCoreId, key, PaymentStatus.AWAITING_OTP);
    }

    static Payment stored(UUID partnerId, UUID linkId, String paymentCoreId, String key, PaymentStatus status) {
        Instant initiated = NOW.minusSeconds(60);
        return Payment.restore(
                UUID.randomUUID(),
                paymentCoreId,
                partnerId,
                linkId,
                "provider-1",
                "account-at-initiation",
                key,
                "fingerprint",
                PaymentType.PAYIN,
                "BANK_PAYIN",
                EUR_100,
                "tx-" + paymentCoreId,
                "ref-1",
                null,
                StubPaymentProcessor.FEE,
                status,
                initiated,
                initiated,
                StubPaymentProcessor.EXPIRES_AT,
                null,
                initiated,
                null);
    }

    private void assertFailure(Command command, PaymentFailure failure) {
        assertThatThrownBy(() -> service.confirm(command))
                .isInstanceOfSatisfying(
                        PaymentException.class, e -> assertThat(e.failure()).isEqualTo(failure));
    }
}
