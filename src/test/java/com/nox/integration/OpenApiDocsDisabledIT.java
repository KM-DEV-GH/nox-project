package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;

import com.nox.integration.support.KeycloakContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Outside the {@code dev} profile, generated API docs and Swagger UI do not exist and are not public. */
class OpenApiDocsDisabledIT extends KeycloakContainerSupport {

    private static final String API_DOCS = "/v3/api-docs";
    private static final String SWAGGER_UI = "/swagger-ui/index.html";
    private static final String DOCS = "/docs";

    @Test
    void docsAreNotPublic() {
        assertProblem(
                client.get().uri(API_DOCS).exchange().expectBody(String.class).returnResult(),
                401,
                "unauthenticated",
                API_DOCS);
        assertProblem(
                client.get().uri(DOCS).exchange().expectBody(String.class).returnResult(),
                401,
                "unauthenticated",
                DOCS);
        assertProblem(
                client.get().uri(SWAGGER_UI).exchange().expectBody(String.class).returnResult(),
                401,
                "unauthenticated",
                SWAGGER_UI);
    }

    @Test
    void docsAreNotServedEvenWithAToken() {
        client.get()
                .uri(API_DOCS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.user("alice"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }
}
