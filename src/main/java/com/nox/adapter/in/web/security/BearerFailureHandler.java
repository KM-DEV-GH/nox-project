package com.nox.adapter.in.web.security;

import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.error.ProblemType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Splits bearer token failures between the caller's fault (401) and the service's fault: when the
 * signing key cannot be obtained, the request fails closed with 503 (FR-011).
 */
@Component
public class BearerFailureHandler implements AuthenticationFailureHandler {

    private final ProblemAuthenticationEntryPoint entryPoint;
    private final ProblemResponseWriter problemWriter;

    public BearerFailureHandler(ProblemAuthenticationEntryPoint entryPoint, ProblemResponseWriter problemWriter) {
        this.entryPoint = entryPoint;
        this.problemWriter = problemWriter;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException, ServletException {
        if (failure instanceof AuthenticationServiceException) {
            SecurityEvents.identityProviderUnavailable();
            problemWriter.write(request, response, ProblemType.IDENTITY_PROVIDER_UNAVAILABLE);
            return;
        }
        entryPoint.commence(request, response, failure);
    }
}
