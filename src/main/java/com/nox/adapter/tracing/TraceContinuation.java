package com.nox.adapter.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Continues the trace of the request that caused asynchronous work (an event listener, a message
 * publication) in a span of its own. Logs written inside therefore carry the request's
 * {@code traceId}, and the span attributes below give the log formatter its {@code messaging} and
 * {@code correlationId} context (constitution Principles VII, XI).
 */
@Component
public class TraceContinuation {

    public static final String CORRELATION_ID = "nox.correlation_id";
    public static final String MESSAGING_OPERATION = "messaging.operation";
    public static final String MESSAGING_EXCHANGE = "messaging.exchange";
    public static final String MESSAGING_ROUTING_KEY = "messaging.routing_key";
    public static final String MESSAGING_QUEUE = "messaging.queue";
    public static final String MESSAGING_MESSAGE_ID = "messaging.message_id";

    private static final int SPAN_ID_BYTES = 8;

    private final Tracer tracer;

    public TraceContinuation(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Runs {@code work} in a span that continues {@code traceId} and carries {@code attributes}. */
    public <T> T call(String traceId, String spanName, Map<String, String> attributes, Supplier<T> work) {
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(traceId)
                .spanId(randomSpanId())
                .sampled(true)
                .build();
        Span span = tracer.spanBuilder().setParent(parent).name(spanName).start();
        attributes.forEach(span::tag);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } finally {
            span.end();
        }
    }

    public void run(String traceId, String spanName, Map<String, String> attributes, Runnable work) {
        call(traceId, spanName, attributes, () -> {
            work.run();
            return null;
        });
    }

    private static String randomSpanId() {
        byte[] random = new byte[SPAN_ID_BYTES];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
