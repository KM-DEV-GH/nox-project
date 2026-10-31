package com.nox.application.fake;

import com.nox.application.port.out.payment.PartnerPaymentSettingsRepository;
import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryPartnerPaymentSettings implements PartnerPaymentSettingsRepository {

    private final Map<UUID, PartnerPaymentAccount> accounts = new HashMap<>();
    private final Map<String, PaymentLimit> limits = new HashMap<>();

    public void account(UUID partnerId, PartnerPaymentAccount account) {
        accounts.put(partnerId, account);
    }

    public void limit(UUID partnerId, PaymentLimit limit) {
        limits.put(key(partnerId, limit.type(), limit.currencyCode()), limit);
    }

    @Override
    public Optional<PartnerPaymentAccount> findAccount(UUID partnerId) {
        return Optional.ofNullable(accounts.get(partnerId));
    }

    @Override
    public Optional<PaymentLimit> findLimit(UUID partnerId, PaymentType type, String currencyCode) {
        return Optional.ofNullable(limits.get(key(partnerId, type, currencyCode)));
    }

    private static String key(UUID partnerId, PaymentType type, String currencyCode) {
        return partnerId + "/" + type + "/" + currencyCode;
    }
}
