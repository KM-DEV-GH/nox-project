package com.nox.infrastructure.config;

import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.APPROVAL_REQUESTED;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.APPROVED;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.EVENTS_EXCHANGE;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.REJECTED;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.WEBHOOKS_EXCHANGE;

import com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessageTransport;
import com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages;
import com.nox.adapter.tracing.TraceContinuation;
import com.nox.application.event.PartnerLinkApprovalRequested;
import com.nox.application.event.PartnerLinkApprovedForPartner;
import com.nox.application.event.PartnerLinkApprovedForUser;
import com.nox.application.event.PartnerLinkEvent;
import com.nox.application.event.PartnerLinkRejectedForPartner;
import com.nox.application.event.PartnerLinkRejectedForUser;
import com.nox.application.event.PartnerLinkRequestSubmitted;
import java.time.Clock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.modulith.events.support.EventExternalizerModuleListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which events leave NOX and where they go (data-model.md, "Domain events and the outbox").
 * Configured here rather than with {@code @Externalized}, so the event records stay Spring-free.
 * {@link PartnerLinkRequestSubmitted} only triggers the user lookup and is never externalized.
 */
@Configuration(proxyBeanMethods = false)
public class PartnerLinkEventExternalizationConfig {

    @Bean
    EventExternalizationConfiguration partnerLinkEventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof PartnerLinkEvent && !(event instanceof PartnerLinkRequestSubmitted))
                .route(PartnerLinkApprovalRequested.class, event -> target(EVENTS_EXCHANGE, APPROVAL_REQUESTED))
                .route(PartnerLinkApprovedForUser.class, event -> target(EVENTS_EXCHANGE, APPROVED))
                .route(PartnerLinkApprovedForPartner.class, event -> target(WEBHOOKS_EXCHANGE, APPROVED))
                .route(PartnerLinkRejectedForUser.class, event -> target(EVENTS_EXCHANGE, REJECTED))
                .route(PartnerLinkRejectedForPartner.class, event -> target(WEBHOOKS_EXCHANGE, REJECTED))
                .mapping(PartnerLinkEvent.class, PartnerLinkMessages::toMessage)
                .build();
    }

    /** Replaces Modulith's RabbitMQ transport, which sets no message properties and ignores publisher confirms. */
    @Bean
    EventExternalizerModuleListener partnerLinkEventExternalizer(
            EventExternalizationConfiguration configuration,
            RabbitTemplate rabbit,
            JsonMapper jsonMapper,
            TraceContinuation traces,
            Clock clock,
            BeanFactory beanFactory) {
        return new EventExternalizerModuleListener(
                configuration, new PartnerLinkMessageTransport(rabbit, jsonMapper, traces, clock), beanFactory);
    }

    private static RoutingTarget target(String exchange, String routingKey) {
        return RoutingTarget.forTarget(exchange).andKey(routingKey);
    }
}
