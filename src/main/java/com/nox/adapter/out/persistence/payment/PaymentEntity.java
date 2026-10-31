package com.nox.adapter.out.persistence.payment;

import com.nox.domain.payment.Money;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Mapping of {@code payment}; {@link #version} serializes state transitions (data-model.md). */
@Entity
@Table(name = "payment")
public class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "payment_core_id")
    private String paymentCoreId;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "user_link_id", nullable = false)
    private UUID userLinkId;

    @Column(name = "provider_user_id_snapshot", nullable = false)
    private String providerUserIdSnapshot;

    @Column(name = "account_id_snapshot", nullable = false)
    private String accountIdSnapshot;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentType type;

    @Column(name = "payment_method", nullable = false)
    private String paymentMethod;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "transaction_reference", nullable = false)
    private String transactionReference;

    private String description;

    @Column(name = "fee_amount")
    private BigDecimal feeAmount;

    @Column(name = "fee_currency_code")
    private String feeCurrencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "payment_core_created_at")
    private Instant paymentCoreCreatedAt;

    @Column(name = "expiration_date")
    private Instant expirationDate;

    @Column(name = "confirmed_date")
    private Instant confirmedDate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected PaymentEntity() {}

    static PaymentEntity of(Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.id = payment.id();
        entity.paymentCoreId = payment.paymentCoreId();
        entity.partnerId = payment.partnerId();
        entity.userLinkId = payment.userLinkId();
        entity.providerUserIdSnapshot = payment.providerUserIdSnapshot();
        entity.accountIdSnapshot = payment.accountIdSnapshot();
        entity.idempotencyKey = payment.idempotencyKey();
        entity.requestFingerprint = payment.requestFingerprint();
        entity.type = payment.type();
        entity.paymentMethod = payment.paymentMethod();
        entity.amount = payment.money().amount();
        entity.currencyCode = payment.money().currencyCode();
        entity.externalId = payment.externalId();
        entity.transactionReference = payment.transactionReference();
        entity.description = payment.description();
        entity.feeAmount = payment.fee() == null ? null : payment.fee().amount();
        entity.feeCurrencyCode = payment.fee() == null ? null : payment.fee().currencyCode();
        entity.status = payment.status();
        entity.createdAt = payment.createdAt();
        entity.paymentCoreCreatedAt = payment.paymentCoreCreatedAt();
        entity.expirationDate = payment.expirationDate();
        entity.confirmedDate = payment.confirmedDate();
        entity.updatedAt = payment.updatedAt();
        entity.version = payment.version();
        return entity;
    }

    Payment toDomain() {
        return Payment.restore(
                id,
                paymentCoreId,
                partnerId,
                userLinkId,
                providerUserIdSnapshot,
                accountIdSnapshot,
                idempotencyKey,
                requestFingerprint,
                type,
                paymentMethod,
                new Money(amount, currencyCode),
                externalId,
                transactionReference,
                description,
                feeAmount == null ? null : new Money(feeAmount, feeCurrencyCode),
                status,
                createdAt,
                paymentCoreCreatedAt,
                expirationDate,
                confirmedDate,
                updatedAt,
                version);
    }
}
