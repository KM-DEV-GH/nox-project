package com.nox.adapter.in.web.security;

import com.nox.adapter.in.web.error.ProblemResponseWriter;
import com.nox.adapter.in.web.error.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 403 for an authenticated caller who lacks a required role (FR-005). */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemWriter;

    public ProblemAccessDeniedHandler(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        SecurityEvents.accessDenied();
        problemWriter.write(request, response, ProblemType.FORBIDDEN);
    }
}
