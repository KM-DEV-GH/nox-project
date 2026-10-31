package com.nox.adapter.in.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.support.TestTracers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemResponseWriterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-10-25T09:21:41.942967Z");
    private static final Set<String> PROBLEM_FIELDS =
            Set.of("type", "title", "status", "detail", "traceId", "timestamp", "instance");

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
    private final ProblemResponseWriter writer = new ProblemResponseWriter(
            new MetaFactory(TestTracers.withTraceId(TRACE_ID), Clock.fixed(NOW, ZoneOffset.UTC)), jsonMapper);

    static Stream<Arguments> catalogue() {
        return Stream.of(
                Arguments.of(ProblemType.UNAUTHENTICATED, 401, "unauthenticated"),
                Arguments.of(ProblemType.FORBIDDEN, 403, "forbidden"),
                Arguments.of(ProblemType.NOT_FOUND, 404, "not_found"),
                Arguments.of(ProblemType.METHOD_NOT_ALLOWED, 405, "method_not_allowed"),
                Arguments.of(ProblemType.NOT_ACCEPTABLE, 406, "not_acceptable"),
                Arguments.of(ProblemType.INTERNAL_ERROR, 500, "internal_error"),
                Arguments.of(ProblemType.IDENTITY_PROVIDER_UNAVAILABLE, 503, "identity_provider_unavailable"));
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void writesProblemToServletResponse(ProblemType type, int status, String title) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request(), response, type);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertProblemBody(jsonMapper.readTree(response.getContentAsString()), type, title);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void buildsProblemResponseEntity(ProblemType type, int status, String title) {
        ResponseEntity<ProblemDetail> entity = writer.toResponseEntity(request(), type);

        assertThat(entity.getStatusCode().value()).isEqualTo(status);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertProblemBody(jsonMapper.valueToTree(entity.getBody()), type, title);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void usesFixedGenericDetailPerType(ProblemType type) {
        assertThat(type.detail()).isNotBlank();
        assertThat(type.detail())
                .doesNotContainIgnoringCase("expired")
                .doesNotContainIgnoringCase("signature")
                .doesNotContainIgnoringCase("audience")
                .doesNotContainIgnoringCase("issuer")
                .doesNotContainIgnoringCase("exception");
    }

    private void assertProblemBody(JsonNode body, ProblemType type, String title) {
        assertThat(fieldNames(body)).containsExactlyInAnyOrderElementsOf(PROBLEM_FIELDS);
        assertThat(body.get("type").asString()).isEqualTo("urn:problem:" + title);
        assertThat(body.get("title").asString()).isEqualTo(title);
        assertThat(body.get("status").asInt()).isEqualTo(type.status().value());
        assertThat(body.get("detail").asString()).isEqualTo(type.detail());
        assertThat(body.get("traceId").asString()).isEqualTo(TRACE_ID);
        assertThat(body.get("timestamp").asString()).isEqualTo("2026-10-25T09:21:41.942967Z");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/me");
    }

    private static List<String> fieldNames(JsonNode body) {
        return body.propertyNames().stream().toList();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setQueryString("a=b");
        return request;
    }
}
