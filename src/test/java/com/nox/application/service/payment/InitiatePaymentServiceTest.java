package com.nox.application.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.application.fake.InMemoryPartnerLinkRepository;
import com.nox.application.fake.InMemoryPartnerPaymentSettings;
import com.nox.application.fake.InMemoryPaymentRepository;
import com.nox.application.fake.StubPaymentProcessor;
import com.nox.application.port.in.payment.InitiatePaymentUseCase.Command;
import com.nox.application.port.in.payment.InitiatePaymentUseCase.Initiation;
import com.nox.application.port.in.payment.LinkReference;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.payment.PaymentProcessorPort.InitiationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.domain.payment.ProcessorPayment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T012: US1 pay-in initiation, its link, limit and idempotency rules (spec FR-001–FR-009, FR-016–FR-017). */
class InitiatePaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final UUID PARTNER_ID = UUID.randomUUID();
    private static final PartnerPaymentAccount ACCOUNT = new PartnerPaymentAccount("provider-1", "account-1");
    private static final Money EUR_100 = Money.of("100.00", "EUR");

    private final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPartnerPaymentSettings settings = new InMemoryPartnerPaymentSettings();
    private final StubPaymentProcessor processor = new StubPaymentProcessor();
    private InitiatePaymentService service = service(Map.of(PaymentType.PAYIN, "BANK_PAYIN"));

    private PartnerLink link;

    @BeforeEach
    void connectedLinkWithAccountAndLimits() {
        link = link(PARTNER_ID, "external-1", PartnerLinkStatus.CONNECTED, NOW.minusSeconds(60));
        settings.account(PARTNER_ID, ACCOUNT);
        settings.limit(PARTNER_ID, limit(PaymentType.PAYIN, "EUR", true, "10", "1000"));
    }

    @Test
    void initiatesAPayinWithTheConfiguredMethodAndTheActiveAccount() {
        Initiation initiation = service.initiate(command("key-1", new LinkReference.ById(link.id()), EUR_100));

        assertThat(initiation.created()).isTrue();
        Payment payment = initiation.payment();
        assertThat(payment.status()).isEqualTo(PaymentStatus.AWAITING_OTP);
        assertThat(payment.paymentCoreId()).isEqualTo(StubPaymentProcessor.PAYMENT_CORE_ID);
        assertThat(payment.userLinkId()).isEqualTo(link.id());
        assertThat(payment.paymentMethod()).isEqualTo("BANK_PAYIN");
        assertThat(payment.fee()).isEqualTo(StubPaymentProcessor.FEE);
        assertThat(payment.expirationDate()).isEqualTo(StubPaymentProcessor.EXPIRES_AT);
        assertThat(payment.providerUserIdSnapshot()).isEqualTo("provider-1");
        assertThat(payment.accountIdSnapshot()).isEqualTo("account-1");
        assertThat(payments.only().status()).isEqualTo(PaymentStatus.AWAITING_OTP);

        InitiationRequest sent = processor.initiations().getFirst();
        assertThat(sent.idempotencyKey()).isEqualTo(payment.id());
        assertThat(sent.type()).isEqualTo(PaymentType.PAYIN);
        assertThat(sent.paymentMethod()).isEqualTo("BANK_PAYIN");
        assertThat(sent.providerUserId()).isEqualTo("provider-1");
        assertThat(sent.accountId()).isEqualTo("account-1");
        assertThat(sent.clientUserId()).isEqualTo(link.userId());
        assertThat(sent.money()).isEqualTo(EUR_100);
        assertThat(sent.referenceNumber()).isEqualTo("reference-1");
    }

    @Test
    void anExternalLinkIdResolvesToThePartnersMostRecentEstablishedLink() {
        PartnerLink newer = link(PARTNER_ID, "external-1", PartnerLinkStatus.CONNECTED, NOW.minusSeconds(10));
        link(PARTNER_ID, "external-1", PartnerLinkStatus.PENDING, NOW.minusSeconds(1));

        Payment payment = service.initiate(command("key-1", new LinkReference.ByExternalId("external-1"), EUR_100))
                .payment();

        assertThat(payment.userLinkId()).isEqualTo(newer.id());
    }

    @Test
    void theMostRecentEstablishedLinkMustBeConnected() {
        link(PARTNER_ID, "external-1", PartnerLinkStatus.REVOKED, NOW.minusSeconds(10));

        assertFailure(
                command("key-1", new LinkReference.ByExternalId("external-1"), EUR_100),
                PaymentFailure.LINK_NOT_CONNECTED);
        assertNothingInitiated();
    }

    @Test
    void anotherPartnersLinkIsNotFoundByEitherIdentifier() {
        PartnerLink foreign = link(UUID.randomUUID(), "external-2", PartnerLinkStatus.CONNECTED, NOW);

        assertFailure(command("key-1", new LinkReference.ById(foreign.id()), EUR_100), PaymentFailure.LINK_NOT_FOUND);
        assertFailure(
                command("key-2", new LinkReference.ByExternalId("external-2"), EUR_100), PaymentFailure.LINK_NOT_FOUND);
        assertNothingInitiated();
    }

    @Test
    void aLinkThatWasNeverEstablishedIsNotFound() {
        PartnerLink pending = link(PARTNER_ID, "external-3", PartnerLinkStatus.PENDING, NOW);

        assertFailure(command("key-1", new LinkReference.ById(pending.id()), EUR_100), PaymentFailure.LINK_NOT_FOUND);
        assertFailure(
                command("key-2", new LinkReference.ById(UUID.randomUUID()), EUR_100), PaymentFailure.LINK_NOT_FOUND);
        assertNothingInitiated();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9.99", "1000.01"})
    void anAmountOutsideTheInclusiveBoundsIsRefused(String amount) {
        assertFailure(command("key-1", byId(), Money.of(amount, "EUR")), PaymentFailure.LIMIT_EXCEEDED);
        assertNothingInitiated();
    }

    @Test
    void theBoundsThemselvesAreAllowed() {
        service.initiate(command("key-1", byId(), Money.of("10", "EUR")));
        service.initiate(command("key-2", byId(), Money.of("1000", "EUR")));

        assertThat(processor.initiations()).hasSize(2);
    }

    @Test
    void aCurrencyWithoutAnEnabledLimitIsRefused() {
        settings.limit(PARTNER_ID, limit(PaymentType.PAYIN, "GBP", false, "1", "1000"));

        assertFailure(command("key-1", byId(), Money.of("50", "USD")), PaymentFailure.LIMIT_EXCEEDED);
        assertFailure(command("key-2", byId(), Money.of("50", "GBP")), PaymentFailure.LIMIT_EXCEEDED);
        assertNothingInitiated();
    }

    @Test
    void theLimitIsPerTransactionType() {
        service = service(Map.of(PaymentType.PAYIN, "BANK_PAYIN", PaymentType.PAYOUT, "BANK_PAYOUT"));

        assertFailure(payout("key-1", EUR_100), PaymentFailure.LIMIT_EXCEEDED);
        assertNothingInitiated();
    }

    @Test
    void aPartnerWithoutAnActiveAccountCannotInitiate() {
        UUID other = UUID.randomUUID();
        PartnerLink otherLink = link(other, "external-4", PartnerLinkStatus.CONNECTED, NOW);
        settings.limit(other, limit(PaymentType.PAYIN, "EUR", true, "1", "1000"));

        assertFailure(
                new Command(
                        other,
                        new LinkReference.ById(otherLink.id()),
                        "key-1",
                        PaymentType.PAYIN,
                        EUR_100,
                        "external-tx-1",
                        "reference-1",
                        null),
                PaymentFailure.NOT_CONFIGURED);
        assertNothingInitiated();
    }

    @Test
    void aTypeWithoutAConfiguredMethodCannotBeInitiated() {
        service = service(Map.of());

        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.NOT_CONFIGURED);
        assertNothingInitiated();
    }

    @Test
    void anIdenticalRetryReturnsTheOriginalPaymentWithoutCallingPaymentCoreAgain() {
        Payment original = service.initiate(command("key-1", byId(), EUR_100)).payment();

        Initiation retry = service.initiate(command("key-1", byId(), Money.of("100", "EUR")));

        assertThat(retry.created()).isFalse();
        assertThat(retry.payment().id()).isEqualTo(original.id());
        assertThat(processor.initiations()).hasSize(1);
        assertThat(payments.size()).isEqualTo(1);
    }

    /** The key resolves to its payment even after the account, the limits or the link changed. */
    @Test
    void aRetryIsAnsweredFromTheStoredPaymentNotTheCurrentSettings() {
        Payment original = service.initiate(command("key-1", byId(), EUR_100)).payment();
        settings.account(PARTNER_ID, new PartnerPaymentAccount("provider-1", "account-2"));
        settings.limit(PARTNER_ID, limit(PaymentType.PAYIN, "EUR", false, "10", "1000"));

        Payment retried = service.initiate(command("key-1", byId(), EUR_100)).payment();

        assertThat(retried.id()).isEqualTo(original.id());
        assertThat(retried.accountIdSnapshot()).isEqualTo("account-1");
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void reusingAKeyWithDifferentDetailsIsAConflict() {
        service.initiate(command("key-1", byId(), EUR_100));

        assertFailure(command("key-1", byId(), Money.of("100.01", "EUR")), PaymentFailure.IDEMPOTENCY_CONFLICT);
        assertFailure(
                new Command(PARTNER_ID, byId(), "key-1", PaymentType.PAYIN, EUR_100, "tx-key-1", "reference-2", null),
                PaymentFailure.IDEMPOTENCY_CONFLICT);
        PartnerLink another = link(PARTNER_ID, "external-5", PartnerLinkStatus.CONNECTED, NOW);
        assertFailure(
                command("key-1", new LinkReference.ById(another.id()), EUR_100), PaymentFailure.IDEMPOTENCY_CONFLICT);
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void theSameKeyFromAnotherPartnerIsANewPayment() {
        service.initiate(command("key-1", byId(), EUR_100));
        UUID other = UUID.randomUUID();
        PartnerLink otherLink = link(other, "external-1", PartnerLinkStatus.CONNECTED, NOW);
        settings.account(other, ACCOUNT);
        settings.limit(other, limit(PaymentType.PAYIN, "EUR", true, "1", "1000"));

        Initiation theirs = service.initiate(new Command(
                other,
                new LinkReference.ById(otherLink.id()),
                "key-1",
                PaymentType.PAYIN,
                EUR_100,
                "external-tx-1",
                "reference-1",
                null));

        assertThat(theirs.created()).isTrue();
        assertThat(payments.size()).isEqualTo(2);
    }

    /** FR-022: the partner's external ID names one payment, whatever the idempotency key. */
    @Test
    void anExternalIdAlreadyUsedByThePartnerIsAConflict() {
        service.initiate(withExternalId("key-1", "tx-shared"));

        assertFailure(withExternalId("key-2", "tx-shared"), PaymentFailure.EXTERNAL_ID_CONFLICT);
        assertThat(processor.initiations()).hasSize(1);
        assertThat(payments.size()).isEqualTo(1);
    }

    @Test
    void aFailedPaymentKeepsItsExternalId() {
        processor.onInitiate(new ProcessorResult.Rejected());
        assertFailure(withExternalId("key-1", "tx-shared"), PaymentFailure.REJECTED);
        processor.onInitiate(request -> StubPaymentProcessor.accepted(request.money()));

        assertFailure(withExternalId("key-2", "tx-shared"), PaymentFailure.EXTERNAL_ID_CONFLICT);
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void aReleasedReservationFreesItsExternalId() {
        processor.onInitiate(new ProcessorResult.NotAttempted());
        assertFailure(withExternalId("key-1", "tx-shared"), PaymentFailure.PROCESSOR_UNAVAILABLE);
        processor.onInitiate(request -> StubPaymentProcessor.accepted(request.money()));

        assertThat(service.initiate(withExternalId("key-2", "tx-shared")).created())
                .isTrue();
    }

    @Test
    void anotherPartnerMayUseTheSameExternalId() {
        service.initiate(withExternalId("key-1", "tx-shared"));
        UUID other = UUID.randomUUID();
        PartnerLink otherLink = link(other, "external-1", PartnerLinkStatus.CONNECTED, NOW);
        settings.account(other, ACCOUNT);
        settings.limit(other, limit(PaymentType.PAYIN, "EUR", true, "1", "1000"));

        Initiation theirs = service.initiate(new Command(
                other,
                new LinkReference.ById(otherLink.id()),
                "key-2",
                PaymentType.PAYIN,
                EUR_100,
                "tx-shared",
                "reference-1",
                null));

        assertThat(theirs.created()).isTrue();
    }

    /** Two requests pass the check together; the database constraint stops the second. */
    @Test
    void aConcurrentRequestWithTheSameExternalIdIsAConflict() {
        payments.interleave(() -> new InitiatePaymentService(
                        links,
                        payments,
                        settings,
                        new StubPaymentProcessor(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Map.of(PaymentType.PAYIN, "BANK_PAYIN"))
                .initiate(withExternalId("key-2", "tx-shared")));

        assertFailure(withExternalId("key-1", "tx-shared"), PaymentFailure.EXTERNAL_ID_CONFLICT);
        assertThat(processor.initiations()).isEmpty();
        assertThat(payments.size()).isEqualTo(1);
    }

    @Test
    void aConcurrentRequestWithTheSameKeyGetsTheWinnersPayment() {
        Command command = command("key-1", byId(), EUR_100);
        payments.interleave(() -> new InitiatePaymentService(
                        links,
                        payments,
                        settings,
                        new StubPaymentProcessor(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Map.of(PaymentType.PAYIN, "BANK_PAYIN"))
                .initiate(command));

        Initiation loser = service.initiate(command);

        assertThat(loser.created()).isFalse();
        assertThat(payments.size()).isEqualTo(1);
        assertThat(processor.initiations()).isEmpty();
    }

    @Test
    void aPaymentStillBeingInitiatedIsInProgressForARetry() {
        processor.onInitiate(request -> {
            assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.IN_PROGRESS);
            return StubPaymentProcessor.accepted(request.money());
        });

        service.initiate(command("key-1", byId(), EUR_100));

        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void aRejectedInitiationFailsAndStaysFailed() {
        processor.onInitiate(new ProcessorResult.Rejected());

        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.REJECTED);
        assertThat(payments.only().status()).isEqualTo(PaymentStatus.FAILED);
        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.REJECTED);
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void anAmbiguousInitiationIsKeptForReconciliationAndNotRepeated() {
        processor.onInitiate(new ProcessorResult.Unknown());

        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.UNRESOLVED);
        assertThat(payments.only().status()).isEqualTo(PaymentStatus.INITIATION_UNKNOWN);
        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.UNRESOLVED);
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void echoedMoneyThatDiffersIsUnresolved() {
        processor.onInitiate(request -> StubPaymentProcessor.accepted(Money.of("99", "EUR")));

        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.UNRESOLVED);
        assertThat(payments.only().status()).isEqualTo(PaymentStatus.INITIATION_UNKNOWN);
    }

    @Test
    void anOpenCircuitReleasesTheKeySoTheSameRequestCanBeRetriedLater() {
        processor.onInitiate(new ProcessorResult.NotAttempted());

        assertFailure(command("key-1", byId(), EUR_100), PaymentFailure.PROCESSOR_UNAVAILABLE);
        assertThat(payments.size()).isZero();

        processor.onInitiate(request -> StubPaymentProcessor.accepted(request.money()));
        assertThat(service.initiate(command("key-1", byId(), EUR_100)).created())
                .isTrue();
    }

    @Test
    void aProcessorFailureStatusIsReturnedAsTheOutcome() {
        processor.onInitiate(new ProcessorResult.Completed(new ProcessorPayment(
                "pc-2", PaymentStatus.FAILED, EUR_100, StubPaymentProcessor.FEE, NOW, null, null)));

        Initiation initiation = service.initiate(command("key-1", byId(), EUR_100));

        assertThat(initiation.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(initiation.payment().paymentCoreId()).isEqualTo("pc-2");
    }

    private InitiatePaymentService service(Map<PaymentType, String> methods) {
        return new InitiatePaymentService(
                links, payments, settings, processor, Clock.fixed(NOW, ZoneOffset.UTC), methods);
    }

    private LinkReference byId() {
        return new LinkReference.ById(link.id());
    }

    private static Command command(String key, LinkReference link, Money money) {
        return new Command(PARTNER_ID, link, key, PaymentType.PAYIN, money, "tx-" + key, "reference-1", "a deposit");
    }

    private Command withExternalId(String key, String externalId) {
        return new Command(PARTNER_ID, byId(), key, PaymentType.PAYIN, EUR_100, externalId, "reference-1", null);
    }

    private Command payout(String key, Money money) {
        return new Command(PARTNER_ID, byId(), key, PaymentType.PAYOUT, money, "tx-" + key, "reference-1", null);
    }

    private PartnerLink link(UUID partnerId, String externalId, PartnerLinkStatus status, Instant createdAt) {
        PartnerLink created = PartnerLink.restore(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                partnerId,
                externalId,
                "licensee-1",
                status,
                NOW.plusSeconds(3600),
                0L,
                createdAt,
                createdAt);
        links.put(created);
        return created;
    }

    private static PaymentLimit limit(PaymentType type, String currency, boolean enabled, String min, String max) {
        return new PaymentLimit(type, currency, enabled, new BigDecimal(min), new BigDecimal(max));
    }

    private void assertFailure(Command command, PaymentFailure failure) {
        assertThatThrownBy(() -> service.initiate(command))
                .isInstanceOfSatisfying(
                        PaymentException.class, e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private void assertNothingInitiated() {
        assertThat(processor.initiations()).isEmpty();
        assertThat(payments.size()).isZero();
    }
}
