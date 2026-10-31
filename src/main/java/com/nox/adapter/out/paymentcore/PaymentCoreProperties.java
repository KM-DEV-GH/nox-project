package com.nox.adapter.out.paymentcore;

import com.nox.adapter.out.http.CircuitBreakerSettings;
import com.nox.adapter.out.http.HttpClientSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for payment-core calls, owned by {@link PaymentCoreAdapter} (constitution Principles X,
 * XIII). Credentials live in the {@code payment-core} OAuth2 client registration, never here.
 *
 * @param baseUrl payment-core's base URL
 * @param registrationId the Spring Security OAuth2 client registration used for its tokens
 * @param connectTimeout connect timeout per attempt
 * @param readTimeout read timeout per attempt
 * @param maxAttempts total attempts for a transient failure, including the first
 * @param backoff delay before the first retry; doubled for each further retry
 * @param circuitBreaker when to stop calling payment-core for a while
 * @param socks5Enabled route payment-core API and token requests through the configured SOCKS5 proxy
 * @param socks5Host SOCKS5 proxy host, required when enabled
 * @param socks5Port SOCKS5 proxy port
 */
@Validated
@ConfigurationProperties("nox.payment.payment-core")
public record PaymentCoreProperties(
        @NotBlank String baseUrl,
        @NotBlank @DefaultValue("payment-core") String registrationId,

        @NotNull @DurationMin(millis = 1) @DefaultValue("2s")
        Duration connectTimeout,

        @NotNull @DurationMin(millis = 1) @DefaultValue("5s")
        Duration readTimeout,

        @Min(1) @DefaultValue("3") int maxAttempts,
        @NotNull @DefaultValue("200ms") Duration backoff,
        @Valid @NotNull @DefaultValue CircuitBreakerSettings circuitBreaker,
        @DefaultValue("false") boolean socks5Enabled,
        @DefaultValue("") String socks5Host,
        @Min(1) @DefaultValue("1080") int socks5Port)
        implements HttpClientSettings {}
