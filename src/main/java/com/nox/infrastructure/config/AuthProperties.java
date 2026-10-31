package com.nox.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for trusting exactly one Keycloak realm (FR-015).
 *
 * <p>Validation runs at startup in every profile, so {@code stage} and {@code prod} (which have no
 * defaults) fail fast when {@code NOX_AUTH_ISSUER_URI} or {@code NOX_AUTH_JWKS_URI} is missing.
 *
 * @param issuerUri exact {@code iss} value expected in tokens
 * @param jwksUri where the realm's signing keys are fetched from
 * @param audience value that must be contained in the token {@code aud} claim
 * @param jwksConnectTimeout connect timeout for key fetches
 * @param jwksReadTimeout read timeout for key fetches
 */
@Validated
@ConfigurationProperties("nox.auth")
public record AuthProperties(
        @NotBlank String issuerUri,
        @NotBlank String jwksUri,
        @NotBlank @DefaultValue("nox-api") String audience,
        @NotNull @DefaultValue("2s") Duration jwksConnectTimeout,
        @NotNull @DefaultValue("2s") Duration jwksReadTimeout) {

    @AssertTrue(message = "JWKS timeouts must be positive")
    public boolean isJwksTimeoutsPositive() {
        return isPositive(jwksConnectTimeout) && isPositive(jwksReadTimeout);
    }

    private static boolean isPositive(Duration duration) {
        return duration == null || duration.isPositive();
    }
}
