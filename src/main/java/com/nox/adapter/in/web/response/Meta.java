package com.nox.adapter.in.web.response;

import java.time.Instant;

/**
 * Metadata shared by success and error responses.
 *
 * @param traceId W3C trace ID of the current request, identical to the log {@code trace.traceId}
 * @param timestamp response time (UTC)
 * @param instance request path, without query string
 */
public record Meta(String traceId, Instant timestamp, String instance) {}
