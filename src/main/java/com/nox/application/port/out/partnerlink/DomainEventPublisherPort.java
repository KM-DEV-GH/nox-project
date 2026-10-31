package com.nox.application.port.out.partnerlink;

/**
 * Publishes an event from {@code com.nox.application.event} as part of the current transaction; it
 * is delivered only if the transaction commits.
 */
public interface DomainEventPublisherPort {

    void publish(Object event);
}
