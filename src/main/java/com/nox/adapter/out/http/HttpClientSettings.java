package com.nox.adapter.out.http;

import java.time.Duration;

/**
 * What every outbound HTTP adapter configures the same way: bounded connections, an optional SOCKS5
 * proxy, and the retry and circuit-breaker limits of {@link ResilientCall}. Each adapter's settings
 * record satisfies it through its own accessors.
 */
public interface HttpClientSettings {

    /** Connect timeout per attempt. */
    Duration connectTimeout();

    /** Read timeout per attempt. */
    Duration readTimeout();

    /** Total attempts for a transient failure, including the first. */
    int maxAttempts();

    /** Delay before the first retry; doubled for each further retry. */
    Duration backoff();

    /** When to stop calling the remote service for a while. */
    CircuitBreakerSettings circuitBreaker();

    /** Whether API and token requests go through the SOCKS5 proxy. */
    boolean socks5Enabled();

    /** SOCKS5 proxy host, required when enabled. */
    String socks5Host();

    /** SOCKS5 proxy port. */
    int socks5Port();
}
