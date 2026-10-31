package com.nox.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Reads the token only from a single {@code Authorization: Bearer} header. Several
 * {@code Authorization} headers are rejected rather than silently using the first one, and tokens
 * in query or form parameters are never accepted.
 */
@Component
public class SingleBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() > 1) {
            throw new InvalidBearerTokenException("Found multiple Authorization headers");
        }
        return delegate.resolve(request);
    }
}
