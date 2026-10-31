package com.nox.adapter.out.persistence.payment;

import com.nox.application.port.out.payment.PartnerPaymentSettingsRepository;
import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaPartnerPaymentSettingsRepository implements PartnerPaymentSettingsRepository {

    private final PartnerPaymentAccountJpaRepository accounts;
    private final PartnerPaymentLimitJpaRepository limits;

    public JpaPartnerPaymentSettingsRepository(
            PartnerPaymentAccountJpaRepository accounts, PartnerPaymentLimitJpaRepository limits) {
        this.accounts = accounts;
        this.limits = limits;
    }

    @Override
    public Optional<PartnerPaymentAccount> findAccount(UUID partnerId) {
        return accounts.findById(partnerId).map(PartnerPaymentAccountEntity::toDomain);
    }

    @Override
    public Optional<PaymentLimit> findLimit(UUID partnerId, PaymentType type, String currencyCode) {
        return limits.findById(new PartnerPaymentLimitEntity.Key(partnerId, type, currencyCode))
                .map(PartnerPaymentLimitEntity::toDomain);
    }
}
