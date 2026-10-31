package com.nox.infrastructure.config;

import com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares only the exchanges NOX publishes to. Queues, bindings and dead-letter exchanges belong
 * to the consuming services (constitution Principle XI, research.md decision 3).
 */
@Configuration(proxyBeanMethods = false)
public class RabbitMqConfig {

    private static final boolean DURABLE = true;
    private static final boolean AUTO_DELETE = false;

    @Bean
    Declarables partnerLinkExchanges() {
        return new Declarables(
                new TopicExchange(PartnerLinkMessages.EVENTS_EXCHANGE, DURABLE, AUTO_DELETE),
                new TopicExchange(PartnerLinkMessages.WEBHOOKS_EXCHANGE, DURABLE, AUTO_DELETE));
    }
}
