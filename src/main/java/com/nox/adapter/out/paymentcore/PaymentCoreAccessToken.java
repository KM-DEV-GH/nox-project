package com.nox.adapter.out.paymentcore;

import java.util.List;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * NOX's own client-credentials token for payment-core (research.md D4): fetched, cached and refreshed
 * by Spring Security, and dropped when payment-core answers 401. The partner's token is never
 * forwarded, and no API key is sent.
 */
final class PaymentCoreAccessToken {

    private static final Authentication NOX =
            new AnonymousAuthenticationToken("nox", "nox", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private PaymentCoreAccessToken() {}

    /** Adds {@code Authorization: Bearer <token>}, with token requests bounded by {@code requestFactory}. */
    static ClientHttpRequestInterceptor interceptor(
            ClientRegistration registration, ClientHttpRequestFactory requestFactory) {
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        OAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        RestClientClientCredentialsTokenResponseClient tokenClient =
                new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.addAll(List.of(
                            new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        ClientCredentialsOAuth2AuthorizedClientProvider provider =
                new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);

        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> registration.getRegistrationId());
        interceptor.setPrincipalResolver(request -> NOX);
        interceptor.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClients));
        return interceptor;
    }
}
