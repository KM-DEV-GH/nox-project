package com.nox.adapter.out.userlookup;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nox.adapter.out.http.CircuitBreakerSettings;
import com.nox.application.port.out.partnerlink.UserLookupResult;
import com.nox.integration.support.OwlWireMockSupport;
import com.nox.support.CapturedLogs;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** Every row of the mapping table in contracts/owl-user-lookup.md. */
class OwlUserLookupAdapterTest {

    private static final String API_KEY = "test-api-key";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final int MAX_ATTEMPTS = 3;

    private final WireMockServer server =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private OwlWireMockSupport owl;
    private OwlUserLookupAdapter adapter;
    private CapturedLogs logs;

    @BeforeEach
    void start() {
        server.start();
        owl = new OwlWireMockSupport(new WireMock(server.port()), API_KEY);
        owl.stubTokenEndpoint();
        adapter = adapter(new CircuitBreakerSettings(50, 20, 10, Duration.ofSeconds(30)));
        logs = CapturedLogs.of(OwlUserLookupAdapter.class);
    }

    private OwlUserLookupAdapter adapter(CircuitBreakerSettings circuitBreaker) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("owl")
                .clientId("nox-test")
                .clientSecret(CLIENT_SECRET)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(server.baseUrl() + "/oauth/token")
                .build();
        OwlProperties properties = new OwlProperties(
                server.baseUrl(),
                API_KEY,
                Duration.ofSeconds(1),
                READ_TIMEOUT,
                MAX_ATTEMPTS,
                Duration.ofMillis(10),
                circuitBreaker,
                false,
                "",
                1080);
        return new OwlUserLookupAdapter(properties, new InMemoryClientRegistrationRepository(registration));
    }

    @AfterEach
    void stop() {
        logs.close();
        server.stop();
    }

    @Test
    void findsTheOneApprovedUserWithBothCredentialsAndAnEncodedEmail() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.userFound(email, "5cc42488-7c7b-4d49-b2d3-d35e9f67335c");

        UserLookupResult result = adapter.lookUpByEmail(email);

        assertThat(result).isEqualTo(new UserLookupResult.Found("5cc42488-7c7b-4d49-b2d3-d35e9f67335c"));
        LoggedRequest request = server.findAll(owl.lookupRequest(email)).getFirst();
        assertThat(request.getUrl())
                .contains("email=" + email.replace("@", "%40").replace("+", "%2B"))
                .contains("page=1")
                .contains("page_size=2")
                .contains("direction=DESC");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + OwlWireMockSupport.ACCESS_TOKEN);
        assertThat(request.getHeader("api-key")).isEqualTo(API_KEY);
        assertNoSecretsLogged(email);
    }

    @Test
    void noUserIsNotFound() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.noUser(email);

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.NotFound());
        assertThat(owl.lookupsFor(email)).isEqualTo(1);
    }

    @Test
    void anUnverifiedUserIsNotFound() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.unverifiedUser(email);

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.NotFound());
        assertThat(owl.lookupsFor(email)).isEqualTo(1);
    }

    @Test
    void anAmbiguousMatchIsNotFoundWithAWarning() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.ambiguousUser(email);

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.NotFound());
        assertThat(owl.lookupsFor(email)).isEqualTo(1);
        assertThat(logs.events())
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertNoSecretsLogged(email);
    }

    @Test
    void anUnsuccessfulAnswerIsRetriedThenFails() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.unsuccessful(email);

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.Failed());
        assertThat(owl.lookupsFor(email)).isEqualTo(MAX_ATTEMPTS);
        assertNoSecretsLogged(email);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void aServerErrorIsRetriedThenFails(int status) {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.status(email, status);

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.Failed());
        assertThat(owl.lookupsFor(email)).isEqualTo(MAX_ATTEMPTS);
        assertNoSecretsLogged(email);
    }

    @Test
    void aTimeoutIsRetriedThenFails() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.slow(email, READ_TIMEOUT.multipliedBy(3));

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.Failed());
        assertThat(owl.lookupsFor(email)).isEqualTo(MAX_ATTEMPTS);
        assertNoSecretsLogged(email);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void aClientErrorFailsAtOnceWithAnError(int status) {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.status(email, status);

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.Failed());
        assertThat(owl.lookupsFor(email)).isEqualTo(1);
        assertThat(logs.events())
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        assertNoSecretsLogged(email);
    }

    /** Once open, the circuit refuses lookups without contacting Owl. */
    @Test
    void anOpenCircuitFailsLookupsWithoutSendingThem() {
        adapter = adapter(new CircuitBreakerSettings(50, 2, 2, Duration.ofMinutes(1)));
        String failing = OwlWireMockSupport.uniqueEmail();
        owl.status(failing, 503);
        adapter.lookUpByEmail(failing);
        String next = OwlWireMockSupport.uniqueEmail();
        owl.userFound(next, "user-1");

        assertThat(adapter.lookUpByEmail(next)).isEqualTo(new UserLookupResult.Failed());
        assertThat(owl.lookupsFor(next)).isZero();
        assertNoSecretsLogged(next);
    }

    @Test
    void anUnavailableTokenEndpointIsRetriedThenFails() {
        server.stubFor(
                post(urlPathEqualTo("/oauth/token")).willReturn(aResponse().withStatus(503)));
        String email = OwlWireMockSupport.uniqueEmail();
        owl.userFound(email, "user-1");

        assertThat(adapter.lookUpByEmail(email)).isEqualTo(new UserLookupResult.Failed());
        assertThat(server.findAll(postRequestedFor(urlPathEqualTo("/oauth/token"))))
                .hasSize(MAX_ATTEMPTS);
        assertThat(owl.lookupsFor(email)).isZero();
        assertNoSecretsLogged(email);
    }

    private void assertNoSecretsLogged(String email) {
        assertThat(logs.allText())
                .doesNotContain(email)
                .doesNotContain(email.replace("@", "%40"))
                .doesNotContain("email=")
                .doesNotContain(OwlWireMockSupport.USER_PATH)
                .doesNotContain(API_KEY)
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(OwlWireMockSupport.ACCESS_TOKEN);
    }
}
