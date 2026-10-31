package com.nox.application.service.payment;

import static com.nox.application.service.payment.ConfirmPaymentServiceTest.awaitingOtp;
import static com.nox.application.service.payment.ConfirmPaymentServiceTest.stored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.application.fake.InMemoryPartnerLinkRepository;
import com.nox.application.fake.InMemoryPaymentRepository;
import com.nox.application.fake.StubPaymentProcessor;
import com.nox.application.port.in.payment.CancelPaymentUseCase.Command;
import com.nox.application.port.in.payment.ConfirmPaymentUseCase;
import com.nox.application.port.in.payment.LinkReference;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.payment.PaymentProcessorPort.CancellationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** T031: US4 cancellation of a payment awaiting the OTP (spec FR-018–FR-021). */
class CancelPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:02:00Z");
    private static final UUID PARTNER_ID = UUID.randomUUID();
    private static final String PAYMENT_CORE_ID = "pc-1";

    private final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final StubPaymentProcessor processor = new StubPaymentProcessor();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CancelPaymentService service = new CancelPaymentService(links, payments, processor, clock);

    private PartnerLink link;

    @BeforeEach
    void paymentAwaitingOtp() {
        link = link(PARTNER_ID, "external-1", PartnerLinkStatus.CONNECTED);
        payments.insert(awaitingOtp(PARTNER_ID, link.id(), PAYMENT_CORE_ID, "key-1"));
    }

    @Test
    void cancelsWithTheOriginalAccountAndMoneyOnlyOncePaymentCoreConfirms() {
        Payment cancelled = service.cancel(command(byId(), PAYMENT_CORE_ID));

        assertThat(cancelled.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CANCELLED);
        CancellationRequest sent = processor.cancellations().getFirst();
        assertThat(sent.paymentCoreId()).isEqualTo(PAYMENT_CORE_ID);
        assertThat(sent.providerUserId()).isEqualTo("provider-1");
        assertThat(sent.accountId()).isEqualTo("account-at-initiation");
        assertThat(sent.money()).isEqualTo(Money.of("100", "EUR"));
        assertThat(sent.toString()).doesNotContain("account-at-initiation");
    }

    @Test
    void cancelsThroughTheExternalLinkId() {
        assertThat(service.cancel(command(new LinkReference.ByExternalId("external-1"), PAYMENT_CORE_ID))
                        .status())
                .isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void aRepeatedCancellationReturnsTheOutcomeWithoutASecondCall() {
        service.cancel(command(byId(), PAYMENT_CORE_ID));

        assertThat(service.cancel(command(byId(), PAYMENT_CORE_ID)).status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(processor.cancellations()).hasSize(1);
    }

    @Test
    void aCancelledPaymentCanNoLongerBeConfirmed() {
        service.cancel(command(byId(), PAYMENT_CORE_ID));
        ConfirmPaymentService confirm = new ConfirmPaymentService(links, payments, processor, clock);

        assertThatThrownBy(() -> confirm.confirm(
                        new ConfirmPaymentUseCase.Command(PARTNER_ID, byId(), PAYMENT_CORE_ID, "123456")))
                .isInstanceOfSatisfying(
                        PaymentException.class, e -> assertThat(e.failure()).isEqualTo(PaymentFailure.NOT_ACTIONABLE));
        assertThat(processor.confirmations()).isEmpty();
    }

    @Test
    void aConfirmedPaymentCannotBeCancelled() {
        new ConfirmPaymentService(links, payments, processor, clock)
                .confirm(new ConfirmPaymentUseCase.Command(PARTNER_ID, byId(), PAYMENT_CORE_ID, "123456"));

        assertFailure(command(byId(), PAYMENT_CORE_ID), PaymentFailure.NOT_ACTIONABLE);
        assertThat(processor.cancellations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CONFIRMED", "FAILED", "EXPIRED"})
    void onlyAPendingPaymentCanBeCancelled(PaymentStatus status) {
        payments.insert(stored(PARTNER_ID, link.id(), "pc-done", "key-2", status));

        assertFailure(command(byId(), "pc-done"), PaymentFailure.NOT_ACTIONABLE);
        assertThat(processor.cancellations()).isEmpty();
    }

    /** Confirmation and cancellation race: the one that claims the payment first wins (FR-019). */
    @Test
    void aConfirmationInFlightBlocksTheCancellation() {
        payments.insert(stored(PARTNER_ID, link.id(), "pc-confirming", "key-2", PaymentStatus.CONFIRMING));

        assertFailure(command(byId(), "pc-confirming"), PaymentFailure.IN_PROGRESS);
        assertThat(processor.cancellations()).isEmpty();
    }

    @Test
    void aConfirmationThatClaimedFirstWinsTheRace() {
        payments.interleave(() -> {
            Payment other = payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow();
            other.beginConfirmation(NOW);
            payments.save(other);
        });

        assertFailure(command(byId(), PAYMENT_CORE_ID), PaymentFailure.NOT_ACTIONABLE);
        assertThat(processor.cancellations()).isEmpty();
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CONFIRMING);
    }

    @Test
    void aPaymentOfAnotherLinkOrPartnerIsNotCancelled() {
        PartnerLink otherLink = link(PARTNER_ID, "external-2", PartnerLinkStatus.CONNECTED);

        assertFailure(
                command(new LinkReference.ById(otherLink.id()), PAYMENT_CORE_ID), PaymentFailure.PAYMENT_NOT_FOUND);
        assertFailure(new Command(UUID.randomUUID(), byId(), PAYMENT_CORE_ID), PaymentFailure.LINK_NOT_FOUND);
        assertThat(processor.cancellations()).isEmpty();
    }

    @Test
    void aRefusalByPaymentCoreLeavesThePaymentPending() {
        processor.onCancel(new ProcessorResult.Rejected());

        assertFailure(command(byId(), PAYMENT_CORE_ID), PaymentFailure.NOT_ACTIONABLE);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.AWAITING_OTP);
    }

    @Test
    void anAmbiguousCancellationIsKeptForReconciliation() {
        processor.onCancel(new ProcessorResult.Unknown());

        assertFailure(command(byId(), PAYMENT_CORE_ID), PaymentFailure.UNRESOLVED);
        assertThat(payments.findByPaymentCoreId(PAYMENT_CORE_ID).orElseThrow().status())
                .isEqualTo(PaymentStatus.CANCELLATION_UNKNOWN);
        assertFailure(command(byId(), PAYMENT_CORE_ID), PaymentFailure.UNRESOLVED);
        assertThat(processor.cancellations()).hasSize(1);
    }

    /** NOX reports what payment-core says, even if it is not a cancellation. */
    @Test
    void paymentCoresAuthoritativeOutcomeIsRecorded() {
        processor.onCancel(
                request -> StubPaymentProcessor.outcome(PAYMENT_CORE_ID, PaymentStatus.EXPIRED, request.money()));

        assertThat(service.cancel(command(byId(), PAYMENT_CORE_ID)).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    private LinkReference byId() {
        return new LinkReference.ById(link.id());
    }

    private static Command command(LinkReference link, String paymentId) {
        return new Command(PARTNER_ID, link, paymentId);
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

    private void assertFailure(Command command, PaymentFailure failure) {
        assertThatThrownBy(() -> service.cancel(command))
                .isInstanceOfSatisfying(
                        PaymentException.class, e -> assertThat(e.failure()).isEqualTo(failure));
    }
}
