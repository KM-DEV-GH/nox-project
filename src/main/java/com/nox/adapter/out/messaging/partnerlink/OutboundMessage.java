package com.nox.adapter.out.messaging.partnerlink;

import java.util.UUID;

/**
 * An event mapped for RabbitMQ: the AMQP metadata plus the {@code data} of the message envelope.
 *
 * @param messageId AMQP {@code message_id}
 * @param traceId {@code traceId} header and {@code meta.traceId}
 * @param correlationId AMQP {@code correlation_id}
 * @param data the event-specific payload (contracts/events.md)
 */
public record OutboundMessage(UUID messageId, String traceId, UUID correlationId, Object data) {}
