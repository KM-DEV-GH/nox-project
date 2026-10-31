package com.nox.adapter.in.web.error;

import com.nox.adapter.in.web.response.Meta;
import com.nox.adapter.in.web.response.MetaFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single producer of error bodies: used by security filters (which run before controllers)
 * and by {@link GlobalExceptionHandler}.
 */
@Component
public class ProblemResponseWriter {

    private final MetaFactory metaFactory;
    private final JsonMapper jsonMapper;

    public ProblemResponseWriter(MetaFactory metaFactory, JsonMapper jsonMapper) {
        this.metaFactory = metaFactory;
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemType type) throws IOException {
        response.setStatus(type.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body(request, type));
    }

    public ResponseEntity<ProblemDetail> toResponseEntity(HttpServletRequest request, ProblemType type) {
        return ResponseEntity.status(type.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(request, type));
    }

    /** {@code traceId} and {@code timestamp} are serialized top-level by Spring's ProblemDetail Jackson mixin. */
    private ProblemDetail body(HttpServletRequest request, ProblemType type) {
        Meta meta = metaFactory.create(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), type.detail());
        problem.setType(URI.create(type.type()));
        problem.setTitle(type.title());
        problem.setInstance(URI.create(meta.instance()));
        problem.setProperty("traceId", meta.traceId());
        problem.setProperty("timestamp", meta.timestamp());
        return problem;
    }
}
