package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.application.port.out.payment.ConcurrentPaymentUpdateException;
import com.nox.application.port.out.payment.DuplicateExternalIdException;
import com.nox.application.port.out.payment.DuplicatePaymentException;
import com.nox.application.port.out.payment.PartnerPaymentSettingsRepository;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.partner.Partner;
import com.nox.domain.payment.Money;
import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import com.nox.domain.payment.ProcessorPayment;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.PartnerFixtures;
import com.nox.integration.support.PaymentFixtures;
import com.nox.integration.support.PaymentFixtures.EstablishedLink;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T005: the payment tables, their constraints, and the JPA adapters, on the Liquibase-built schema. */
class PaymentPersistenceIT extends KeycloakContainerSupport {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PartnerPaymentSettingsRepository settings;

    private PaymentFixtures fixtures;
    private Partner partner;
    private EstablishedLink link;

    @BeforeEach
    void createPartner() {
        partner = new PartnerFixtures(jdbc).active();
        fixtures = new PaymentFixtures(jdbc);
        link = fixtures.connectedLink(partner);
    }

    @Test
    void readsTheActiveAccountAndItsReplacement() {
        assertThat(settings.findAccount(partner.id())).isEmpty();

        fixtures.activeAccount(partner, "provider-1", "account-1");
        assertThat(settings.findAccount(partner.id())).contains(new PartnerPaymentAccount("provider-1", "account-1"));

        fixtures.activeAccount(partner, "provider-1", "account-2");
        assertThat(settings.findAccount(partner.id())).contains(new PartnerPaymentAccount("provider-1", "account-2"));
    }

    @Test
    void readsTheLimitForPartnerTypeAndCurrency() {
        fixtures.limit(partner, PaymentType.PAYIN, "EUR", true, "10", "100");
        fixtures.limit(partner, PaymentType.PAYOUT, "EUR", false, "5", "50");

        PaymentLimit payin =
                settings.findLimit(partner.id(), PaymentType.PAYIN, "EUR").orElseThrow();
        assertThat(payin.enabled()).isTrue();
        assertThat(payin.minimumAmount()).isEqualByComparingTo("10");
        assertThat(payin.maximumAmount()).isEqualByComparingTo("100");
        assertThat(settings.findLimit(partner.id(), PaymentType.PAYOUT, "EUR"))
                .get()
                .extracting(PaymentLimit::enabled)
                .isEqualTo(false);
        assertThat(settings.findLimit(partner.id(), PaymentType.PAYIN, "USD")).isEmpty();
    }

    @Test
    void limitBoundsAreInclusiveAndChecked() {
        fixtures.limit(partner, PaymentType.PAYIN, "EUR", true, "10", "10");

        assertThatThrownBy(() -> fixtures.limit(partner, PaymentType.PAYIN, "USD", true, "0", "10"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.limit(partner, PaymentType.PAYIN, "GBP", true, "10", "9.99"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.limit(partner, PaymentType.PAYIN, "EUR", true, "1", "5"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settings.findLimit(partner.id(), PaymentType.PAYIN, "EUR")
                        .orElseThrow()
                        .allows(Money.of("10", "EUR")))
                .isTrue();
    }

    @Test
    void anAccountOrLimitNeedsAKnownPartner() {
        Partner unknown = new Partner(UUID.randomUUID(), "unknown", "Unknown", null, null, NOW, null);

        assertThatThrownBy(() -> fixtures.activeAccount(unknown, "provider-1", "account-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.limit(unknown, PaymentType.PAYIN, "EUR", true, "1", "2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roundTripsAPaymentThroughItsLifecycle() {
        Payment inserted = payments.insert(newPayment("key-" + UUID.randomUUID()));
        assertThat(inserted.version()).isZero();
        assertThat(payments.findByPartnerAndIdempotencyKey(partner.id(), inserted.idempotencyKey()))
                .get()
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(inserted);

        String paymentCoreId = "pc-" + UUID.randomUUID();
        inserted.recordInitiation(
                new ProcessorPayment(
                        paymentCoreId,
                        PaymentStatus.AWAITING_OTP,
                        Money.of("123.45", "EUR"),
                        Money.of("1.23", "EUR"),
                        NOW,
                        NOW.plusSeconds(600),
                        null),
                NOW);
        Payment awaiting = payments.save(inserted);

        assertThat(awaiting.version()).isEqualTo(1L);
        Payment found = payments.findByPaymentCoreId(paymentCoreId).orElseThrow();
        assertThat(found)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(awaiting);
        assertThat(found.money().amount()).isEqualByComparingTo("123.45");
        assertThat(found.fee().sameAs(Money.of("1.23", "EUR"))).isTrue();
        assertThat(found.expirationDate()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void theIdempotencyKeyIsUniquePerPartnerOnly() {
        String key = "key-" + UUID.randomUUID();
        payments.insert(newPayment(key));

        assertThatThrownBy(() -> payments.insert(newPayment(key))).isInstanceOf(DuplicatePaymentException.class);

        Partner other = new PartnerFixtures(jdbc).active();
        EstablishedLink otherLink = fixtures.connectedLink(other);
        Payment otherPartners = Payment.initiate(
                UUID.randomUUID(),
                other.id(),
                otherLink.linkId(),
                new PartnerPaymentAccount("provider-1", "account-1"),
                key,
                "fingerprint",
                PaymentType.PAYIN,
                "BANK",
                Money.of("123.45", "EUR"),
                "external-1",
                "reference-1",
                null,
                NOW);
        assertThat(payments.insert(otherPartners).version()).isZero();
    }

    /** FR-022: one payment per partner external ID, whatever the idempotency key. */
    @Test
    void theExternalIdIsUniquePerPartnerOnly() {
        Payment first = payments.insert(newPayment("key-" + UUID.randomUUID()));

        assertThatThrownBy(() -> payments.insert(newPayment("key-" + UUID.randomUUID(), first.externalId())))
                .isInstanceOf(DuplicateExternalIdException.class);
        assertThat(payments.existsByPartnerAndExternalId(partner.id(), first.externalId()))
                .isTrue();
        assertThat(payments.existsByPartnerAndExternalId(partner.id(), "tx-unused"))
                .isFalse();

        Partner other = new PartnerFixtures(jdbc).active();
        EstablishedLink otherLink = fixtures.connectedLink(other);
        Payment otherPartners = Payment.initiate(
                UUID.randomUUID(),
                other.id(),
                otherLink.linkId(),
                new PartnerPaymentAccount("provider-1", "account-1"),
                "key-" + UUID.randomUUID(),
                "fingerprint",
                PaymentType.PAYIN,
                "BANK",
                Money.of("123.45", "EUR"),
                first.externalId(),
                "reference-1",
                null,
                NOW);
        assertThat(payments.insert(otherPartners).version()).isZero();
    }

    @Test
    void thePaymentCoreIdIsUnique() {
        String paymentCoreId = "pc-" + UUID.randomUUID();
        Payment first = payments.insert(newPayment("key-" + UUID.randomUUID()));
        Payment second = payments.insert(newPayment("key-" + UUID.randomUUID()));
        first.recordInitiation(processor(paymentCoreId), NOW);
        payments.save(first);
        second.recordInitiation(processor(paymentCoreId), NOW);

        assertThatThrownBy(() -> payments.save(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aConcurrentTransitionFailsOneWriter() {
        Payment stored = payments.insert(newPayment("key-" + UUID.randomUUID()));
        stored.recordInitiation(processor("pc-" + UUID.randomUUID()), NOW);
        payments.save(stored);
        Payment confirming =
                payments.findByPaymentCoreId(stored.paymentCoreId()).orElseThrow();
        Payment cancelling =
                payments.findByPaymentCoreId(stored.paymentCoreId()).orElseThrow();

        assertThat(confirming.beginConfirmation(NOW)).isTrue();
        assertThat(cancelling.beginCancellation(NOW)).isTrue();
        payments.save(confirming);

        assertThatThrownBy(() -> payments.save(cancelling)).isInstanceOf(ConcurrentPaymentUpdateException.class);
        assertThat(payments.findByPaymentCoreId(stored.paymentCoreId())
                        .orElseThrow()
                        .status())
                .isEqualTo(PaymentStatus.CONFIRMING);
    }

    @Test
    void aReleasedReservationIsGone() {
        Payment reserved = payments.insert(newPayment("key-" + UUID.randomUUID()));

        payments.delete(reserved.id());

        assertThat(payments.findByPartnerAndIdempotencyKey(partner.id(), reserved.idempotencyKey()))
                .isEmpty();
    }

    @Test
    void theDatabaseRejectsAnUnknownStatusOrANonPositiveAmount() {
        Payment stored = payments.insert(newPayment("key-" + UUID.randomUUID()));

        assertThatThrownBy(() -> jdbc.sql("update payment set status = 'SETTLED' where id = :id")
                        .param("id", stored.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update payment set amount = 0 where id = :id")
                        .param("id", stored.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update payment set user_link_id = :link where id = :id")
                        .param("link", UUID.randomUUID())
                        .param("id", stored.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Payment newPayment(String key) {
        return newPayment(key, "tx-" + key);
    }

    private Payment newPayment(String key, String externalId) {
        return Payment.initiate(
                UUID.randomUUID(),
                partner.id(),
                link.linkId(),
                new PartnerPaymentAccount("provider-1", "account-1"),
                key,
                "fingerprint",
                PaymentType.PAYIN,
                "BANK",
                Money.of("123.45", "EUR"),
                externalId,
                "reference-1",
                "a deposit",
                NOW);
    }

    private static ProcessorPayment processor(String paymentCoreId) {
        return new ProcessorPayment(
                paymentCoreId,
                PaymentStatus.AWAITING_OTP,
                Money.of("123.45", "EUR"),
                Money.of("1.23", "EUR"),
                NOW,
                null,
                null);
    }
}
