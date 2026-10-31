package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.adapter.in.web.error.ProblemType;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** Principle VI: every documented error of the feature has the single problem shape. */
class PartnerLinkErrorResponsesIT extends PartnerLinkIntegrationSupport {

    @Test
    void invalidRequestsAreBadRequestWithoutDetailsOfTheCheck() {
        EntityExchangeResult<String> invalidEmail = submit(
                "{\"externalId\":\"e\",\"email\":\"nope\",\"licenseeId\":\"l\"}",
                UUID.randomUUID().toString());
        EntityExchangeResult<String> missingKey =
                submit("{\"externalId\":\"e\",\"email\":\"a@example.test\"," + "\"licenseeId\":\"l\"}", null);

        JsonNode body = assertProblem(invalidEmail, 400, "bad_request", PartnerLinkApi.REQUESTS);
        assertThat(body.get("detail").asString()).isEqualTo(ProblemType.BAD_REQUEST.detail());
        assertThat(invalidEmail.getResponseBody()).doesNotContain("nope");
        assertProblem(missingKey, 400, "bad_request", PartnerLinkApi.REQUESTS);
        assertThat(jdbc.sql("select count(*) from partner_link_request where partner_id = :partnerId")
                        .param("partnerId", partner.id())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void unauthenticatedAndForbidden() {
        String path = PartnerLinkApi.USER_LINKS;

        assertProblem(
                client.get().uri(path).exchange().expectBody(String.class).returnResult(),
                401,
                "unauthenticated",
                path);
        assertProblem(api.getAsPartner(partner, path), 403, "forbidden", path);
    }

    @Test
    void unknownResourcesAreNotFound() {
        String request = PartnerLinkApi.REQUESTS + "/" + UUID.randomUUID();
        String link = PartnerLinkApi.USER_LINKS + "/" + UUID.randomUUID();

        assertProblem(api.getAsPartner(partner, request), 404, "not_found", request);
        assertProblem(api.getAsInternalBff(UUID.randomUUID().toString(), link), 404, "not_found", link);
    }

    @Test
    void conflictsUseTheTwoDocumentedTypes() {
        String alice = TOKENS.user("alice");
        Link expiring = scenarios.pendingLink(partner);
        Link pending = scenarios.pendingLink(partner);

        EntityExchangeResult<String> notActionable =
                api.postAsInternalBff(pending.userId(), pending.path() + "/revoke");
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        EntityExchangeResult<String> expired = api.postAsInternalBff(expiring.userId(), expiring.path() + "/approve");

        JsonNode expiredBody = assertProblem(expired, 409, "link_expired", expiring.path() + "/approve");
        assertThat(expiredBody.get("detail").asString())
                .isEqualTo("This link request has expired. Ask the provider to send a new one.");
        JsonNode notActionableBody =
                assertProblem(notActionable, 409, "link_not_actionable", pending.path() + "/revoke");
        assertThat(notActionableBody.get("detail").asString())
                .isEqualTo("This link is not in a state that allows this action.");
    }

    @Test
    void wrongMethodIsMethodNotAllowed() {
        String path = PartnerLinkApi.USER_LINKS + "/" + UUID.randomUUID() + "/approve";

        EntityExchangeResult<String> result =
                api.getAsInternalBff(UUID.randomUUID().toString(), path);

        assertProblem(result, 405, "method_not_allowed", path);
        assertThat(result.getResponseHeaders().getAllow()).isNotEmpty();
    }

    private EntityExchangeResult<String> submit(String body, String idempotencyKey) {
        RestTestClient.RequestBodySpec request = client.post()
                .uri(PartnerLinkApi.REQUESTS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.bff())
                .header("x-service-id", partner.clientId());
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }
}
