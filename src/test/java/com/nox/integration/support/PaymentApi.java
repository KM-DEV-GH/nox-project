package com.nox.integration.support;

import com.nox.domain.partner.Partner;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the partner payment endpoints the way the partner BFF does. */
public class PaymentApi {

    public static final String USER_LINKS = "/api/v1/user-partner-links";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final TestTokens tokens;

    public PaymentApi(RestTestClient client, TestTokens tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    /** {@code /user-partner-links/{userLinkId}/payments}. */
    public static String byLinkId(UUID userLinkId) {
        return USER_LINKS + "/" + userLinkId + "/payments";
    }

    /** {@code /user-partner-links/by-external-id/{externalLinkId}/payments}. */
    public static String byExternalId(String externalLinkId) {
        return USER_LINKS + "/by-external-id/" + externalLinkId + "/payments";
    }

    public static String confirmation(String payments, String paymentId) {
        return payments + "/" + paymentId + "/confirmation";
    }

    public static String cancellation(String payments, String paymentId) {
        return payments + "/" + paymentId + "/cancellation";
    }

    public static String body(String type, String amount, String currency, String reference) {
        return """
                {"type":"%s","money":{"amount":%s,"currencyCode":"%s"},"externalId":"tx-%s",
                 "transactionReference":"%s","description":"Deposit to PAM wallet"}""".formatted(type, amount, currency, reference, reference);
    }

    public EntityExchangeResult<String> initiate(Partner partner, String payments, String key, String body) {
        return post(tokens.bff(), partner.clientId(), payments, key, body);
    }

    public EntityExchangeResult<String> confirm(Partner partner, String payments, String paymentId, String code) {
        return post(tokens.bff(), partner.clientId(), confirmation(payments, paymentId), null, """
                {"code":"%s"}""".formatted(code));
    }

    public EntityExchangeResult<String> cancel(Partner partner, String payments, String paymentId) {
        return post(tokens.bff(), partner.clientId(), cancellation(payments, paymentId), null, null);
    }

    /** A {@code POST} with any token (or none, when {@code null}) and any headers. */
    public EntityExchangeResult<String> post(
            String token, String serviceId, String path, String idempotencyKey, String body) {
        RestTestClient.RequestBodySpec request = client.post().uri(path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (serviceId != null) {
            request.header("x-service-id", serviceId);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange().expectBody(String.class).returnResult();
    }

    public static JsonNode json(EntityExchangeResult<String> result) {
        return JSON.readTree(result.getResponseBody());
    }

    public static String paymentIdOf(EntityExchangeResult<String> result) {
        return json(result).get("data").get("paymentId").asString();
    }
}
