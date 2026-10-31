package com.nox.infrastructure.config;

import com.nox.adapter.out.identityprovider.SigningKeySource;
import com.nox.adapter.out.identityprovider.SigningKeyStateTracker;
import com.nox.application.port.out.partnerlink.DomainEventPublisherPort;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.application.port.out.partnerlink.TraceIdSource;
import com.nox.application.port.out.payment.PartnerPaymentSettingsRepository;
import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.application.service.partnerlink.SubmitPartnerLinkRequestService;
import com.nox.application.service.payment.InitiatePaymentService;
import java.net.URI;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans that need values from the shared settings in this package or lifecycle hooks (constitution
 * Principle X): application services, which may not see configuration types, and beans with
 * lifecycle hooks. Everything else is component-scanned; adapters bind their own settings records
 * and are never built here (enforced by {@code ArchitectureTest}). Services get plain constructor
 * arguments; they never see {@link AuthProperties}, {@link PartnerLinkProperties} or
 * {@link PartnerPaymentProperties}.
 */
@Configuration(proxyBeanMethods = false)
public class ConfiguredBeansConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    SigningKeySource signingKeySource(AuthProperties properties, SigningKeyStateTracker tracker) {
        return new SigningKeySource(
                URI.create(properties.jwksUri()),
                properties.jwksConnectTimeout(),
                properties.jwksReadTimeout(),
                tracker);
    }

    @Bean
    SubmitPartnerLinkRequestService submitPartnerLinkRequestService(
            PartnerLinkRequestRepository requests,
            DomainEventPublisherPort events,
            TraceIdSource traces,
            Clock clock,
            PartnerLinkProperties properties) {
        return new SubmitPartnerLinkRequestService(requests, events, traces, clock, properties.approvalWindow());
    }

    @Bean
    InitiatePaymentService initiatePaymentService(
            PartnerLinkRepository links,
            PaymentRepository payments,
            PartnerPaymentSettingsRepository settings,
            PaymentProcessorPort processor,
            Clock clock,
            PartnerPaymentProperties properties) {
        return new InitiatePaymentService(links, payments, settings, processor, clock, properties.methods());
    }
}
