package com.nox.adapter.out.messaging.partnerlink;

import com.nox.adapter.tracing.TraceContinuation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.modulith.events.support.EventExternalizationTransport;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends externalized partner link events to RabbitMQ (constitution Principle XI): persistent JSON in
 * the {@code { data, meta }} envelope, with {@code message_id}, {@code correlation_id}, {@code type}
 * and a {@code traceId} header. A publication completes only after the broker confirms it, so an
 * unconfirmed message stays incomplete in the registry and is resubmitted.
 */
public class PartnerLinkMessageTransport implements EventExternalizationTransport {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerLinkMessageTransport.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(10);
    private static final String TRACE_ID_HEADER = "traceId";

    private final RabbitTemplate rabbit;
    private final JsonMapper jsonMapper;
    private final TraceContinuation traces;
    private final Clock clock;

    public PartnerLinkMessageTransport(
            RabbitTemplate rabbit, JsonMapper jsonMapper, TraceContinuation traces, Clock clock) {
        this.rabbit = rabbit;
        this.jsonMapper = jsonMapper;
        this.traces = traces;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<?> externalize(Object payload, RoutingTarget target) {
        OutboundMessage message = (OutboundMessage) payload;
        Map<String, String> context = Map.of(
                TraceContinuation.MESSAGING_OPERATION, "publish",
                TraceContinuation.MESSAGING_EXCHANGE, target.getTarget(),
                TraceContinuation.MESSAGING_ROUTING_KEY, target.getKey(),
                TraceContinuation.MESSAGING_MESSAGE_ID, message.messageId().toString(),
                TraceContinuation.CORRELATION_ID, message.correlationId().toString());
        return traces.call(message.traceId(), "partner-link publish", context, () -> send(message, target));
    }

    private CompletableFuture<?> send(OutboundMessage message, RoutingTarget target) {
        CorrelationData confirmation = new CorrelationData(message.messageId().toString());
        try {
            rabbit.send(target.getTarget(), target.getKey(), toAmqp(message, target.getKey()), confirmation);
            awaitAck(confirmation);
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            LOG.atWarn().setCause(e).log("message publication failed");
            return CompletableFuture.failedFuture(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
        LOG.info("message published");
        return CompletableFuture.completedFuture(null);
    }

    private static void awaitAck(CorrelationData confirmation)
            throws ExecutionException, InterruptedException, TimeoutException {
        CorrelationData.Confirm confirm =
                confirmation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) {
            throw new AmqpException("Publisher confirm was negative: " + confirm.reason());
        }
    }

    private Message toAmqp(OutboundMessage message, String routingKey) {
        MessageEnvelope envelope =
                new MessageEnvelope(message.data(), new MessageMeta(message.traceId(), clock.instant(), routingKey));
        return MessageBuilder.withBody(jsonMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(message.messageId().toString())
                .setCorrelationId(message.correlationId().toString())
                .setType(routingKey)
                .setHeader(TRACE_ID_HEADER, message.traceId())
                .build();
    }

    /** The same envelope as API responses; {@code instance} holds the routing key (contracts/events.md). */
    record MessageEnvelope(Object data, MessageMeta meta) {}

    record MessageMeta(String traceId, Instant timestamp, String instance) {}
}
