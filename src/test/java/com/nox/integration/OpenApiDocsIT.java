package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.KeycloakContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where enabled (the {@code dev} profile), generated OpenAPI docs and Swagger UI are public, because
 * a browser cannot attach a Bearer token to the UI's own requests. The API itself stays protected.
 */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class OpenApiDocsIT extends KeycloakContainerSupport {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void docsDescribeThePartnerLinkApiWithBearerAuth() {
        String body = client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        JsonNode docs = jsonMapper.readTree(body);
        assertThat(docs.get("paths").propertyNames())
                .contains(
                        "/api/v1/partner-link-requests",
                        "/api/v1/partner-link-requests/{requestId}",
                        "/api/v1/partner-links/{linkId}",
                        "/api/v1/user-partner-links/{linkId}/approve");
        JsonNode approveParameters = docs.get("paths")
                .get("/api/v1/user-partner-links/{linkId}/approve")
                .get("post")
                .get("parameters");
        assertThat(approveParameters
                        .valueStream()
                        .map(parameter -> parameter.get("in").asString() + ":"
                                + parameter.get("name").asString()))
                .containsExactlyInAnyOrder("path:linkId", "header:X-User-ID");
        assertThat(docs.get("components")
                        .get("securitySchemes")
                        .get("bearer")
                        .get("scheme")
                        .asString())
                .isEqualTo("bearer");
        assertThat(body).doesNotContain("callingPartner");
    }

    @Test
    void swaggerUiIsServed() {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }

    @Test
    void docsRedirectsToSwaggerUi() {
        client.get()
                .uri("/docs")
                .exchange()
                .expectStatus()
                .isFound()
                .expectHeader()
                .location("/swagger-ui/index.html");
    }

    @Test
    void theApiItselfStillRequiresAToken() {
        assertProblem(
                client.get()
                        .uri("/api/v1/user-partner-links")
                        .exchange()
                        .expectBody(String.class)
                        .returnResult(),
                401,
                "unauthenticated",
                "/api/v1/user-partner-links");
    }
}
