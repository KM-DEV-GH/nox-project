package com.nox.adapter.out.userlookup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nox.application.port.out.partnerlink.UserLookupPort;
import com.nox.application.port.out.partnerlink.UserLookupResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Looks a user up in Owl by email, exactly as contracts/owl-user-lookup.md describes. Transient
 * failures are retried with exponential backoff (Spring's {@link RetryTemplate}); a rejected call
 * fails at once. The email travels in the query string, so neither the URL nor any exception that
 * could contain it is ever logged.
 */
@Component
public class OwlUserLookupAdapter implements UserLookupPort {

    private static final Logger LOG = LoggerFactory.getLogger(OwlUserLookupAdapter.class);
    private static final String USER_PATH = "/api/v1/service/user";
    private static final String API_KEY_HEADER = "api-key";
    private static final String OWL_REGISTRATION = "owl";
    private static final String APPROVED = "APPROVED";
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final RestClient owl;
    private final RetryTemplate retry;

    public OwlUserLookupAdapter(OwlProperties properties, ClientRegistrationRepository registrations) {
        this.owl = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(OwlAccessToken.requestFactory(properties))
                .requestInterceptor(
                        OwlAccessToken.interceptor(registrations.findByRegistrationId(OWL_REGISTRATION), properties))
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .build();
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxAttempts() - 1L)
                .delay(properties.backoff())
                .multiplier(BACKOFF_MULTIPLIER)
                .includes(TransientFailure.class)
                .build());
    }

    @Override
    public UserLookupResult lookUpByEmail(String email) {
        try {
            return retry.execute(() -> lookUpOnce(email));
        } catch (RetryException e) {
            LOG.warn("user lookup failed after retries");
            return new UserLookupResult.Failed();
        }
    }

    private UserLookupResult lookUpOnce(String email) {
        UserPage page;
        try {
            page = owl.get()
                    .uri(uri -> uri.path(USER_PATH)
                            .queryParam("email", "{email}")
                            .queryParam("page", 1)
                            .queryParam("page_size", 2)
                            .queryParam("direction", "DESC")
                            .build(email))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(UserPage.class);
        } catch (HttpClientErrorException e) {
            LOG.atError().addKeyValue("status", e.getStatusCode().value()).log("user lookup rejected by Owl");
            return new UserLookupResult.Failed();
        } catch (HttpServerErrorException | ResourceAccessException | OAuth2AuthorizationException e) {
            throw new TransientFailure();
        }
        if (page == null || !page.success()) {
            throw new TransientFailure();
        }
        return toResult(page.items());
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

    /** A failure worth retrying. Carries no cause: causes may contain the request URL and thus the email. */
    private static final class TransientFailure extends RuntimeException {

        private TransientFailure() {
            super("Owl did not answer", null, false, false);
        }
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
