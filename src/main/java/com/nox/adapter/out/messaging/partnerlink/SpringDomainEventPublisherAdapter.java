package com.nox.adapter.out.messaging.partnerlink;

import com.nox.application.port.out.partnerlink.DomainEventPublisherPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes through Spring, so Spring Modulith's event publication registry stores the event in the
 * current transaction and delivers it only after commit (research.md decision 3a).
 */
@Component
public class SpringDomainEventPublisherAdapter implements DomainEventPublisherPort {

    private final ApplicationEventPublisher publisher;

    public SpringDomainEventPublisherAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(Object event) {
        publisher.publishEvent(event);
    }
}
