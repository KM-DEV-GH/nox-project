package com.nox.adapter.out.userlookup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nox.adapter.out.http.HttpCallFailure;
import com.nox.adapter.out.http.HttpClients;
import com.nox.adapter.out.http.ResilientCall;
import com.nox.application.port.out.partnerlink.UserLookupPort;
import com.nox.application.port.out.partnerlink.UserLookupResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Looks a user up in Owl by email, exactly as contracts/owl-user-lookup.md describes, through
 * {@link ResilientCall}: bounded timeouts, a circuit breaker, and retries with exponential backoff;
 * a rejected call fails at once. The email travels in the query string, so neither the URL nor any
 * exception that could contain it is ever logged.
 */
@Component
public class OwlUserLookupAdapter implements UserLookupPort {

    private static final Logger LOG = LoggerFactory.getLogger(OwlUserLookupAdapter.class);
    private static final String USER_PATH = "/api/v1/service/user";
    private static final String API_KEY_HEADER = "api-key";
    private static final String OWL_REGISTRATION = "owl";
    private static final String APPROVED = "APPROVED";

    private final RestClient owl;
    private final ResilientCall resilient;

    public OwlUserLookupAdapter(OwlProperties properties, ClientRegistrationRepository registrations) {
        this.owl = HttpClients.restClientBuilder(
                        properties.baseUrl(), properties, registrations.findByRegistrationId(OWL_REGISTRATION))
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .build();
        this.resilient = new ResilientCall("owl", properties);
    }

    @Override
    public UserLookupResult lookUpByEmail(String email) {
        try {
            UserPage page = resilient.execute(() -> lookUp(email), UserPage::success);
            return toResult(page.items());
        } catch (HttpCallFailure failure) {
            if (failure.kind() == HttpCallFailure.Kind.REJECTED) {
                LOG.atError().addKeyValue("status", failure.status()).log("user lookup rejected by Owl");
            } else {
                LOG.warn("user lookup failed after retries");
            }
            return new UserLookupResult.Failed();
        }
    }

    private UserPage lookUp(String email) {
        return owl.get()
                .uri(uri -> uri.path(USER_PATH)
                        .queryParam("email", "{email}")
                        .queryParam("page", 1)
                        .queryParam("page_size", 2)
                        .queryParam("direction", "DESC")
                        .build(email))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(UserPage.class);
    }

    private static UserLookupResult toResult(List<UserItem> items) {
        if (items.size() > 1) {
            LOG.warn("user lookup matched several users");
            return new UserLookupResult.NotFound();
        }
        if (items.size() == 1 && APPROVED.equals(items.getFirst().verificationStatus())) {
            UserItem user = items.getFirst();
            return new UserLookupResult.Found(user.id(), user.keycloakId());
        }
        return new UserLookupResult.NotFound();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserPage(boolean success, Data data) {

        List<UserItem> items() {
            return data == null || data.items() == null ? List.of() : data.items();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(List<UserItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserItem(
            String id,
            @JsonProperty("keycloak_id") String keycloakId,
            @JsonProperty("verification_status") String verificationStatus) {}
}
