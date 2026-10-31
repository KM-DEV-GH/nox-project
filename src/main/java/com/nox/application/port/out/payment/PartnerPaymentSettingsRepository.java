package com.nox.application.port.out.payment;

import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.PaymentLimit;
import com.nox.domain.payment.PaymentType;
import java.util.Optional;
import java.util.UUID;

/** Reads the internally managed payment account and limits; nothing here is partner-writable. */
public interface PartnerPaymentSettingsRepository {

    Optional<PartnerPaymentAccount> findAccount(UUID partnerId);

    Optional<PaymentLimit> findLimit(UUID partnerId, PaymentType type, String currencyCode);
}
