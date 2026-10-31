package com.nox.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/** Micrometer {@link Tracer} test doubles. */
public final class TestTracers {

    private TestTracers() {}

    /** A tracer whose current span carries the given trace ID. */
    public static Tracer withTraceId(String traceId) {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        return tracer;
    }

    /** A tracer with no current span. */
    public static Tracer withoutSpan() {
        return mock(Tracer.class);
    }
}
