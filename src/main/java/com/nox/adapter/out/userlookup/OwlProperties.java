package com.nox.adapter.out.userlookup;

import com.nox.adapter.out.http.CircuitBreakerSettings;
import com.nox.adapter.out.http.HttpClientSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the Owl user lookup, owned by {@link OwlUserLookupAdapter}.
 *
 * @param baseUrl Owl's base URL
 * @param apiKey secret sent as the {@code api-key} header; bound only from {@code NOX_OWL_API_KEY}
 * @param connectTimeout connect timeout per attempt
 * @param readTimeout read timeout per attempt
 * @param maxAttempts total attempts for a transient failure, including the first
 * @param backoff delay before the first retry; doubled for each further retry
 * @param circuitBreaker when to stop calling Owl for a while
 * @param socks5Enabled route Owl API and token requests through the configured SOCKS5 proxy
 * @param socks5Host SOCKS5 proxy host, required when enabled
 * @param socks5Port SOCKS5 proxy port
 */
@Validated
@ConfigurationProperties("nox.partner-link.owl")
public record OwlProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotNull @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DefaultValue("3s") Duration readTimeout,
        @Min(1) @DefaultValue("4") int maxAttempts,
        @NotNull @DefaultValue("500ms") Duration backoff,
        @Valid @NotNull @DefaultValue CircuitBreakerSettings circuitBreaker,
        @DefaultValue("false") boolean socks5Enabled,
        @DefaultValue("") String socks5Host,
        @Min(1) @DefaultValue("1080") int socks5Port)
        implements HttpClientSettings {

    /** Never prints the api-key. */
    @Override
    public String toString() {
        return "OwlProperties[baseUrl=" + baseUrl + ", apiKey=redacted]";
    }
}
