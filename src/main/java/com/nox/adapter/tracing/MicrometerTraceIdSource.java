package com.nox.adapter.tracing;

import com.nox.application.port.out.partnerlink.TraceIdSource;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** The current span's trace ID, or a fresh random one when no span is active. */
@Component
public class MicrometerTraceIdSource implements TraceIdSource {

    private static final int TRACE_ID_BYTES = 16;

    private final Tracer tracer;

    public MicrometerTraceIdSource(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span != null) {
            return span.context().traceId();
        }
        byte[] random = new byte[TRACE_ID_BYTES];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
