package com.nox.integration.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.UUID;

/**
 * Stubs Owl's user lookup and its OAuth2 token endpoint, following contracts/owl-user-lookup.md.
 * User stubs match only a request that carries both the bearer token and the api-key, so a request
 * missing either gets WireMock's 404. Each stub is keyed by email, so tests sharing the server do
 * not interfere. Placeholder credentials only.
 */
public class OwlWireMockSupport {

    public static final String ACCESS_TOKEN = "test-access-token";
    public static final String USER_PATH = "/api/v1/service/user";

    private final WireMock owl;
    private final String apiKey;

    public OwlWireMockSupport(WireMock owl, String apiKey) {
        this.owl = owl;
        this.apiKey = apiKey;
    }

    /** A {@link WireMock} client for the shared Owl container. */
    public static OwlWireMockSupport forContainer() {
        OwlWireMockSupport support = new OwlWireMockSupport(
                new WireMock(InfrastructureContainers.OWL.getHost(), InfrastructureContainers.owlPort()),
                InfrastructureContainers.OWL_API_KEY);
        support.stubTokenEndpoint();
        return support;
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "+test@example.test";
    }

    public void stubTokenEndpoint() {
        owl.register(post(urlPathEqualTo(InfrastructureContainers.OWL_TOKEN_PATH))
                .willReturn(okJson("""
                {"access_token":"%s","token_type":"Bearer","expires_in":300}""".formatted(ACCESS_TOKEN))));
    }

    /** Exactly one {@code APPROVED} user. */
    public void userFound(String email, String userId) {
        owl.register(lookup(email).willReturn(okJson(page(item(userId, "APPROVED")))));
    }

    public void noUser(String email) {
        owl.register(lookup(email).willReturn(okJson(page())));
    }

    /** Exactly one user, but not {@code APPROVED}. */
    public void unverifiedUser(String email) {
        owl.register(lookup(email).willReturn(okJson(page(item(UUID.randomUUID().toString(), "PENDING")))));
    }

    /** Two users with the same email. */
    public void ambiguousUser(String email) {
        owl.register(lookup(email)
                .willReturn(okJson(page(
                        item(UUID.randomUUID().toString(), "APPROVED"),
                        item(UUID.randomUUID().toString(), "APPROVED")))));
    }

    public void unsuccessful(String email) {
        owl.register(lookup(email).willReturn(okJson("""
                {"success":false,"status":500,"data":null}""")));
    }

    public void status(String email, int status) {
        owl.register(lookup(email).willReturn(aResponse().withStatus(status)));
    }

    /** Answers too late for the configured read timeout. */
    public void slow(String email, Duration delay) {
        owl.register(lookup(email).willReturn(okJson(page()).withFixedDelay((int) delay.toMillis())));
    }

    /** The first call times out; later calls find the user. */
    public void timesOutThenFinds(String email, Duration delay, String userId) {
        String scenario = "recovery-" + email;
        owl.register(lookup(email)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(page()).withFixedDelay((int) delay.toMillis()))
                .willSetStateTo("recovered"));
        owl.register(lookup(email)
                .inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(page(item(userId, "APPROVED")))));
    }

    /** Lookups made for {@code email}, with or without credentials. */
    public int lookupsFor(String email) {
        return owl.find(lookupRequest(email)).size();
    }

    public RequestPatternBuilder lookupRequest(String email) {
        return getRequestedFor(urlPathEqualTo(USER_PATH)).withQueryParam("email", equalTo(email));
    }

    private MappingBuilder lookup(String email) {
        return get(urlPathEqualTo(USER_PATH))
                .withQueryParam("email", equalTo(email))
                .withQueryParam("page", equalTo("1"))
                .withQueryParam("page_size", equalTo("2"))
                .withQueryParam("direction", equalTo("DESC"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withHeader("api-key", equalTo(apiKey));
    }

    private static ResponseDefinitionBuilder okJson(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static String page(String... items) {
        return """
                {"success":true,"status":200,"data":{"items":[%s]},
                 "meta":{"request_id":"18","timestamp":"2026-10-26T08:54:19.229Z",
                  "pagination":{"page":1,"page_size":2,"total_items":%d,"total_pages":1,"has_next":false,"has_previous":false}}}
                """.formatted(String.join(",", items), items.length);
    }

    private static String item(String userId, String verificationStatus) {
        return """
                {"id":"%s","created_at":"2026-10-09T10:01:50.191Z","updated_at":"2026-10-09T10:01:52.765Z",
                 "keycloak_id":"11111111-2222-3333-4444-555555555555","identifier":"7985222028","type":"INDIVIDUAL",
                 "verification_status":"%s","origin":"TONIVA"}""".formatted(userId, verificationStatus);
    }
}
