package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.adapter.in.web.error.ProblemType;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.TestOnlyPingController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** Constitution Principle VI: 404, 405, 406 and 500 problem responses for authenticated callers. */
class ErrorResponsesIT extends KeycloakContainerSupport {

    @Test
    void unknownPathIsNotFound() {
        String path = "/api/v1/does-not-exist";
        EntityExchangeResult<String> result = client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertProblem(result, 404, "not_found", path);
    }

    @Test
    void unsupportedMethodIsMethodNotAllowedWithAllowHeader() {
        EntityExchangeResult<String> result = client.post()
                .uri(TestOnlyPingController.PING)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertProblem(result, 405, "method_not_allowed", TestOnlyPingController.PING);
        assertThat(result.getResponseHeaders().getAllow()).isNotEmpty();
    }

    @Test
    void unsatisfiableAcceptIsNotAcceptable() {
        EntityExchangeResult<String> result = client.get()
                .uri(TestOnlyPingController.PING)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .accept(MediaType.APPLICATION_XML)
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertProblem(result, 406, "not_acceptable", TestOnlyPingController.PING);
    }

    @Test
    void unexpectedFailureIsInternalErrorWithGenericDetail() {
        EntityExchangeResult<String> result = client.get()
                .uri(TestOnlyPingController.BOOM)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectBody(String.class)
                .returnResult();

        JsonNode body = assertProblem(result, 500, "internal_error", TestOnlyPingController.BOOM);
        assertThat(body.get("detail").asString()).isEqualTo(ProblemType.INTERNAL_ERROR.detail());
        assertThat(result.getResponseBody())
                .doesNotContain("IllegalStateException")
                .doesNotContain("internal details");
    }

    private static String bearer() {
        return "Bearer " + TOKENS.user("alice");
    }
}
