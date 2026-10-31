package com.nox.integration.support;

import com.nox.domain.partner.Partner;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the partner link endpoints the way the partner BFF and the internal BFF do. */
public class PartnerLinkApi {

    public static final String REQUESTS = "/api/v1/partner-link-requests";
    public static final String PARTNER_LINKS = "/api/v1/partner-links";
    public static final String USER_LINKS = "/api/v1/user-partner-links";
    public static final String USER_ID_HEADER = "X-User-ID";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final TestTokens tokens;

    public PartnerLinkApi(RestTestClient client, TestTokens tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public EntityExchangeResult<String> submit(
            Partner partner, String idempotencyKey, String externalId, String email) {
        return client.post()
                .uri(REQUESTS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.bff())
                .header("x-service-id", partner.clientId())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"externalId":"%s","email":"%s","licenseeId":"nox-gaming-eu"}""".formatted(externalId, email))
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    /** Submits a fresh request and returns its {@code requestId}. */
    public UUID submit(Partner partner, String email) {
        return requestIdOf(submit(partner, UUID.randomUUID().toString(), "external-" + UUID.randomUUID(), email));
    }

    public EntityExchangeResult<String> getAsPartner(Partner partner, String path) {
        return client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.bff())
                .header("x-service-id", partner.clientId())
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    /** The internal BFF calling for the user whose internal (Owl) id is {@code userId}. */
    public EntityExchangeResult<String> getAsInternalBff(String userId, String path) {
        return client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.internalBff())
                .header(USER_ID_HEADER, userId)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    /** As {@link #getAsInternalBff(String, String)}, with {@code POST}. */
    public EntityExchangeResult<String> postAsInternalBff(String userId, String path) {
        return client.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.internalBff())
                .header(USER_ID_HEADER, userId)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    public static UUID requestIdOf(EntityExchangeResult<String> accepted) {
        return UUID.fromString(json(accepted).get("data").get("requestId").asString());
    }

    public static JsonNode json(EntityExchangeResult<String> result) {
        return JSON.readTree(result.getResponseBody());
    }
}
