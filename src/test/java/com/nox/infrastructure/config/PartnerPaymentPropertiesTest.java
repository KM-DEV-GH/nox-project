package com.nox.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.adapter.out.paymentcore.PaymentCoreProperties;
import com.nox.domain.payment.PaymentType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The payment methods, read only by {@link ConfiguredBeansConfig} (T003, research.md D6), and the
 * application.yaml wiring of payment-core's settings and OAuth2 client (research.md D4).
 */
class PartnerPaymentPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PaymentPropertiesOnly.class);

    @Test
    void bindsTheMethodPerType() {
        runner.withPropertyValues("nox.payment.payin-method=BANK_PAYIN", "nox.payment.payout-method=BANK_PAYOUT")
                .run(context -> assertThat(
                                context.getBean(PartnerPaymentProperties.class).methods())
                        .containsExactlyInAnyOrderEntriesOf(
                                Map.of(PaymentType.PAYIN, "BANK_PAYIN", PaymentType.PAYOUT, "BANK_PAYOUT")));
    }

    /** A missing method is not a startup failure: initiating that type is refused instead (spec edge case). */
    @Test
    void aBlankMethodIsLeftOutOfTheConfiguredMethods() {
        runner.withPropertyValues("nox.payment.payin-method=BANK_PAYIN")
                .run(context -> assertThat(
                                context.getBean(PartnerPaymentProperties.class).methods())
                        .containsOnlyKeys(PaymentType.PAYIN));
    }

    /** Client credentials only, values from the environment; no API key is configured (contracts/payment-core.md). */
    @Test
    void applicationYamlWiresPaymentCoreFromTheEnvironment() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        PaymentAndClientProperties.class, ValidationAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--NOX_PAYMENT_PAYIN_METHOD=BANK_PAYIN",
                        "--NOX_PAYMENT_CORE_BASE_URL=http://payment-core.test",
                        "--NOX_PAYMENT_CORE_CLIENT_ID=nox-payments",
                        "--NOX_PAYMENT_CORE_TOKEN_URI=http://idp.test/token",
                        "--NOX_PAYMENT_CORE_SOCKS5_ENABLED=true",
                        "--NOX_PAYMENT_CORE_SOCKS5_HOST=proxy.test",
                        "--NOX_PAYMENT_CORE_SOCKS5_PORT=1081",
                        // Every registration needs a client ID to bind, Owl's included.
                        "--NOX_OWL_CLIENT_ID=nox-owl")) {
            OAuth2ClientProperties clients = context.getBean(OAuth2ClientProperties.class);
            OAuth2ClientProperties.Registration registration =
                    clients.getRegistration().get("payment-core");
            assertThat(registration.getAuthorizationGrantType()).isEqualTo("client_credentials");
            assertThat(registration.getClientId()).isEqualTo("nox-payments");
            assertThat(clients.getProvider().get("payment-core").getTokenUri()).isEqualTo("http://idp.test/token");
            assertThat(context.getBean(PartnerPaymentProperties.class).methods())
                    .containsOnlyKeys(PaymentType.PAYIN);
            PaymentCoreProperties paymentCore = context.getBean(PaymentCoreProperties.class);
            assertThat(paymentCore.baseUrl()).isEqualTo("http://payment-core.test");
            assertThat(paymentCore.socks5Enabled()).isTrue();
            assertThat(paymentCore.socks5Host()).isEqualTo("proxy.test");
            assertThat(paymentCore.socks5Port()).isEqualTo(1081);
            assertThat(context.getEnvironment().containsProperty("nox.payment.payment-core.api-key"))
                    .isFalse();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PartnerPaymentProperties.class)
    static class PaymentPropertiesOnly {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        PartnerPaymentProperties.class,
        PaymentCoreProperties.class,
        OAuth2ClientProperties.class
    })
    static class PaymentAndClientProperties {}
}
