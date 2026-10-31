package com.nox.adapter.in.web.response;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Builds {@link Meta} from the current trace, the injected clock, and the request path. */
@Component
public class MetaFactory {

    private static final int TRACE_ID_BYTES = 16;

    private final Tracer tracer;
    private final Clock clock;

    public MetaFactory(Tracer tracer, Clock clock) {
        this.tracer = tracer;
        this.clock = clock;
    }

    public Meta create(HttpServletRequest request) {
        return new Meta(currentTraceId(), clock.instant(), request.getRequestURI());
    }

    /** The current trace ID, or a fresh random one when no span is active, so responses always carry one. */
    private String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span != null) {
            return span.context().traceId();
        }
        byte[] random = new byte[TRACE_ID_BYTES];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
