package com.nox.application.port.out.partnerlink;

/** The trace ID of the work in progress, carried on messages so they can be traced to their request. */
public interface TraceIdSource {

    String currentTraceId();
}
