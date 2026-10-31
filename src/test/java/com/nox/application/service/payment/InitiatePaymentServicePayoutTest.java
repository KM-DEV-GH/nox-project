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
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T019: US2 pay-out uses its own method and limits, with pay-in's link and idempotency rules. */
class InitiatePaymentServicePayoutTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final UUID PARTNER_ID = UUID.randomUUID();
    private static final Money EUR_75 = Money.of("75", "EUR");

    private final InMemoryPartnerLinkRepository links = new InMemoryPartnerLinkRepository();
    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryPartnerPaymentSettings settings = new InMemoryPartnerPaymentSettings();
    private final StubPaymentProcessor processor = new StubPaymentProcessor();
    private final InitiatePaymentService service = new InitiatePaymentService(
            links,
            payments,
            settings,
            processor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Map.of(PaymentType.PAYIN, "BANK_PAYIN", PaymentType.PAYOUT, "BANK_PAYOUT"));

    private PartnerLink link;

    @BeforeEach
    void connectedLinkWithPayoutLimits() {
        link = PartnerLink.restore(
                UUID.randomUUID(),
                "owl-user-1",
                "keycloak-1",
                PARTNER_ID,
                "external-1",
                "licensee-1",
                PartnerLinkStatus.CONNECTED,
                NOW,
                0L,
                NOW,
                NOW);
        links.put(link);
        settings.account(PARTNER_ID, new PartnerPaymentAccount("provider-1", "account-1"));
        settings.limit(PARTNER_ID, limit(PaymentType.PAYOUT, "EUR", true, "50", "500"));
        settings.limit(PARTNER_ID, limit(PaymentType.PAYIN, "EUR", true, "1", "10"));
    }

    @Test
    void aPayoutUsesThePayoutMethodAndTheAccountSnapshot() {
        Initiation initiation = service.initiate(payout("key-1", EUR_75, new LinkReference.ByExternalId("external-1")));

        assertThat(initiation.created()).isTrue();
        assertThat(initiation.payment().type()).isEqualTo(PaymentType.PAYOUT);
        assertThat(initiation.payment().paymentMethod()).isEqualTo("BANK_PAYOUT");
        assertThat(initiation.payment().accountIdSnapshot()).isEqualTo("account-1");
        assertThat(processor.initiations().getFirst().type()).isEqualTo(PaymentType.PAYOUT);
        assertThat(processor.initiations().getFirst().paymentMethod()).isEqualTo("BANK_PAYOUT");
    }

    /** 75 EUR is outside the pay-in bounds but inside the pay-out ones: limits are per type. */
    @Test
    void payoutLimitsAreIndependentOfPayinLimits() {
        assertThat(service.initiate(payout("key-1", EUR_75, byId())).created()).isTrue();
        assertFailure(payout("key-2", Money.of("49.99", "EUR"), byId()), PaymentFailure.LIMIT_EXCEEDED);
        assertFailure(payout("key-3", Money.of("500.01", "EUR"), byId()), PaymentFailure.LIMIT_EXCEEDED);
        assertFailure(payout("key-4", Money.of("75", "USD"), byId()), PaymentFailure.LIMIT_EXCEEDED);
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void aDisabledPayoutCurrencyIsRefused() {
        settings.limit(PARTNER_ID, limit(PaymentType.PAYOUT, "EUR", false, "50", "500"));

        assertFailure(payout("key-1", EUR_75, byId()), PaymentFailure.LIMIT_EXCEEDED);
        assertThat(payments.size()).isZero();
    }

    @Test
    void aPayoutKeyIsIdempotentWithinThePartnerAndConflictsAcrossTypes() {
        service.initiate(payout("key-1", EUR_75, byId()));

        assertThat(service.initiate(payout("key-1", EUR_75, byId())).created()).isFalse();
        assertFailure(
                new Command(
                        PARTNER_ID,
                        byId(),
                        "key-1",
                        PaymentType.PAYIN,
                        Money.of("5", "EUR"),
                        "tx-key-1",
                        "ref-1",
                        null),
                PaymentFailure.IDEMPOTENCY_CONFLICT);
        assertThat(processor.initiations()).hasSize(1);
    }

    @Test
    void aPayoutWithoutAConfiguredMethodIsRefused() {
        InitiatePaymentService payinOnly = new InitiatePaymentService(
                links,
                payments,
                settings,
                processor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Map.of(PaymentType.PAYIN, "BANK_PAYIN"));

        assertThatThrownBy(() -> payinOnly.initiate(payout("key-1", EUR_75, byId())))
                .isInstanceOfSatisfying(
                        PaymentException.class, e -> assertThat(e.failure()).isEqualTo(PaymentFailure.NOT_CONFIGURED));
    }

    private LinkReference byId() {
        return new LinkReference.ById(link.id());
    }

    private static Command payout(String key, Money money, LinkReference link) {
        return new Command(PARTNER_ID, link, key, PaymentType.PAYOUT, money, "tx-" + key, "ref-1", "a withdrawal");
    }

    private static PaymentLimit limit(PaymentType type, String currency, boolean enabled, String min, String max) {
        return new PaymentLimit(type, currency, enabled, new BigDecimal(min), new BigDecimal(max));
    }

    private void assertFailure(Command command, PaymentFailure failure) {
        assertThatThrownBy(() -> service.initiate(command))
                .isInstanceOfSatisfying(
                        PaymentException.class, e -> assertThat(e.failure()).isEqualTo(failure));
    }
}
