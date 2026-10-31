package com.nox.adapter.out.persistence.payment;

import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-only mapping of {@code partner_payment_limit}; limits are managed out of band. */
@Entity
@Table(name = "partner_payment_limit")
@IdClass(PartnerPaymentLimitEntity.Key.class)
public class PartnerPaymentLimitEntity {

    @Id
    @Column(name = "partner_id")
    private UUID partnerId;

    @Id
    @Enumerated(EnumType.STRING)
    private PaymentType type;

    @Id
    @Column(name = "currency_code")
    private String currencyCode;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "minimum_amount", nullable = false)
    private BigDecimal minimumAmount;

    @Column(name = "maximum_amount", nullable = false)
    private BigDecimal maximumAmount;

    protected PartnerPaymentLimitEntity() {}

    PaymentLimit toDomain() {
        return new PaymentLimit(type, currencyCode, enabled, minimumAmount, maximumAmount);
    }

    /** The composite primary key {@code (partner_id, type, currency_code)}. */
    public record Key(UUID partnerId, PaymentType type, String currencyCode) implements Serializable {}
}
