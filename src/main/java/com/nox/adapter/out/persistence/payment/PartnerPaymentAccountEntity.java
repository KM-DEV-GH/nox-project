package com.nox.adapter.out.persistence.payment;

import com.nox.domain.payment.PartnerPaymentAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Read-only mapping of {@code partner_payment_account}; accounts are managed out of band. */
@Entity
@Table(name = "partner_payment_account")
public class PartnerPaymentAccountEntity {

    @Id
    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PartnerPaymentAccountEntity() {}

    PartnerPaymentAccount toDomain() {
        return new PartnerPaymentAccount(providerUserId, accountId);
    }
}
