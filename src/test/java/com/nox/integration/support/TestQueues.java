package com.nox.integration.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Test-only queues bound to NOX's exchanges. NOX itself declares no queues (Principle XI: consumers
 * own their queues), so integration tests bind their own to observe what was published.
 */
public class TestQueues {

    private static final Duration POLL = Duration.ofMillis(200);
    private static final int UNUSED_QUEUE_TTL_MILLIS = 300_000;

    private final AmqpAdmin admin;
    private final RabbitTemplate rabbit;

    public TestQueues(AmqpAdmin admin, RabbitTemplate rabbit) {
        this.admin = admin;
        this.rabbit = rabbit;
    }

    /**
     * Declares a fresh queue bound to {@code exchange} with {@code routingKey}. RabbitMQ 4 no longer
     * allows transient shared queues, so it is durable (it survives a broker restart) and expires
     * once unused.
     */
    public String bind(String exchange, String routingKey) {
        Queue queue = QueueBuilder.durable("nox-test-" + UUID.randomUUID())
                .expires(UNUSED_QUEUE_TTL_MILLIS)
                .build();
        admin.declareQueue(queue);
        admin.declareBinding(
                BindingBuilder.bind(queue).to(new TopicExchange(exchange)).with(routingKey));
        return queue.getName();
    }

    /** All messages in {@code queue} that match, collected until {@code timeout} has passed. */
    public List<Message> collect(String queue, Predicate<Message> matching, Duration timeout) {
        List<Message> matches = new ArrayList<>();
        Instant until = Instant.now().plus(timeout);
        while (Instant.now().isBefore(until)) {
            Message message = rabbit.receive(queue, POLL.toMillis());
            if (message != null && matching.test(message)) {
                matches.add(message);
            }
        }
        return matches;
    }

    /** The first matching message, waiting at most {@code timeout}. */
    public Message awaitFirst(String queue, Predicate<Message> matching, Duration timeout) {
        Instant until = Instant.now().plus(timeout);
        while (Instant.now().isBefore(until)) {
            Message message = rabbit.receive(queue, POLL.toMillis());
            if (message != null && matching.test(message)) {
                return message;
            }
        }
        throw new AssertionError("No matching message on " + queue + " within " + timeout);
    }

    public static Predicate<Message> withMessageId(UUID messageId) {
        return message ->
                messageId.toString().equals(message.getMessageProperties().getMessageId());
    }
}
