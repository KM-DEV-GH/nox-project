package com.nox.adapter.in.web.security;

import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 for a missing or invalid token. The challenge is a plain {@code Bearer} without
 * {@code error} parameters, so the response never reveals which check failed (FR-014).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BEARER_CHALLENGE = "Bearer";

    private final ProblemResponseWriter problemWriter;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        SecurityEvents.authenticationFailed(FailureReason.of(failure));
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
        problemWriter.write(request, response, ProblemType.UNAUTHENTICATED);
    }
}
