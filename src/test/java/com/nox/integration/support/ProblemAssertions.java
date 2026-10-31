package com.nox.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Assertions for {@code application/problem+json} responses (contract {@code Problem}). */
public final class ProblemAssertions {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> REQUIRED = Set.of("type", "title", "detail", "traceId", "timestamp", "instance");

    private ProblemAssertions() {}

    /** Asserts the response is a well-formed problem of the given title and returns its body. */
    public static JsonNode assertProblem(
            EntityExchangeResult<String> result, int status, String title, String instance) {
        assertThat(result.getStatus().value()).isEqualTo(status);
        assertThat(result.getResponseHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .isTrue());
        JsonNode body = JSON.readTree(result.getResponseBody());
        ContractAssertions.assertMatchesSchema(body, "Problem");
        assertThat(body.propertyNames()).containsAll(REQUIRED);
        assertThat(body.get("type").asString())
                .isEqualTo("urn:problem:" + title)
                .matches("^urn:problem:[a-z_]+$");
        assertThat(body.get("title").asString()).isEqualTo(title);
        assertThat(body.get("detail").asString()).isNotBlank();
        assertThat(body.get("traceId").asString()).matches("^[0-9a-f]{32}$");
        assertThat(body.get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(body.get("instance").asString()).isEqualTo(instance);
        return body;
    }
}
