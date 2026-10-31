package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.DomainEventPublisherPort;
import java.util.ArrayList;
import java.util.List;

public class RecordingEventPublisher implements DomainEventPublisherPort {

    private final List<Object> events = new ArrayList<>();

    public List<Object> events() {
        return List.copyOf(events);
    }

    public <T> List<T> eventsOfType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Override
    public void publish(Object event) {
        events.add(event);
    }
}
