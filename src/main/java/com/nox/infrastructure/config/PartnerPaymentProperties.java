package com.nox.infrastructure.config;

import com.nox.domain.payment.PaymentType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The payment-core method per transaction type (research.md D6). Read only by
 * {@link ConfiguredBeansConfig}, which hands the values to the initiation service; the payment-core
 * connection under {@code nox.payment.payment-core} is bound by the adapter's own settings record.
 *
 * @param payinMethod payment-core method for pay-ins; blank refuses pay-ins (spec edge case)
 * @param payoutMethod payment-core method for pay-outs; blank refuses pay-outs
 */
@Validated
@ConfigurationProperties("nox.payment")
public record PartnerPaymentProperties(
        @DefaultValue("") String payinMethod, @DefaultValue("") String payoutMethod) {

    /** The configured method per type; a type without one is absent. */
    public Map<PaymentType, String> methods() {
        Map<PaymentType, String> methods = new EnumMap<>(PaymentType.class);
        if (!payinMethod.isBlank()) {
            methods.put(PaymentType.PAYIN, payinMethod);
        }
        if (!payoutMethod.isBlank()) {
            methods.put(PaymentType.PAYOUT, payoutMethod);
        }
        return methods;
    }
}
