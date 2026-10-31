package com.nox.adapter.in.web.security;

import java.util.Collection;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Internal reason for a security failure, logged as the {@code reason} of a security event
 * (FR-012). Derived only from the exception, never from token contents, and never returned to the
 * caller (FR-014).
 */
enum FailureReason {
    MISSING_TOKEN,
    MALFORMED_TOKEN,
    INVALID_SIGNATURE,
    INVALID_ISSUER,
    INVALID_AUDIENCE,
    TOKEN_EXPIRED,
    TOKEN_NOT_YET_VALID,
    JWKS_UNAVAILABLE,
    MISSING_ROLE;

    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    static FailureReason of(AuthenticationException failure) {
        if (failure instanceof AuthenticationServiceException) {
            return JWKS_UNAVAILABLE;
        }
        if (!(failure instanceof OAuth2AuthenticationException)) {
            return MISSING_TOKEN;
        }
        JwtValidationException validation = causeOfType(failure, JwtValidationException.class);
        if (validation != null) {
            return ofValidationErrors(validation.getErrors());
        }
        BadJwtException badJwt = causeOfType(failure, BadJwtException.class);
        if (badJwt != null && !String.valueOf(badJwt.getMessage()).contains("Malformed")) {
            return INVALID_SIGNATURE;
        }
        return MALFORMED_TOKEN;
    }

    private static FailureReason ofValidationErrors(Collection<OAuth2Error> errors) {
        for (OAuth2Error error : errors) {
            String description = String.valueOf(error.getDescription());
            if (description.startsWith("Jwt expired")) {
                return TOKEN_EXPIRED;
            }
            if (description.startsWith("Jwt used before")) {
                return TOKEN_NOT_YET_VALID;
            }
            if (description.contains("iss claim")) {
                return INVALID_ISSUER;
            }
            if (description.contains("aud claim")) {
                return INVALID_AUDIENCE;
            }
        }
        return MALFORMED_TOKEN;
    }

    private static <T extends Throwable> @Nullable T causeOfType(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
