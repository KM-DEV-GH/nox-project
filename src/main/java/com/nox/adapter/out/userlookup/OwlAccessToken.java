package com.nox.adapter.out.userlookup;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * Spring Security's OAuth2 client-credentials support for Owl calls (research.md decision 10): the
 * token is fetched, cached and refreshed by Spring, and dropped when Owl answers 401. Lookups run
 * outside any user's request, so every call uses the same service principal.
 */
public final class OwlAccessToken {

    private static final Authentication NOX =
            new AnonymousAuthenticationToken("nox", "nox", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private OwlAccessToken() {}

    /** Adds {@code Authorization: Bearer <token>} for {@code registration}, with bounded token requests. */
    public static ClientHttpRequestInterceptor interceptor(ClientRegistration registration, OwlProperties properties) {
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        OAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        ClientCredentialsOAuth2AuthorizedClientProvider provider =
                new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient(properties));
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

    private static RestClientClientCredentialsTokenResponseClient tokenClient(OwlProperties properties) {
        RestClientClientCredentialsTokenResponseClient client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .requestFactory(requestFactory(properties))
                .messageConverters(converters -> {
                    converters.clear();
                    converters.addAll(List.of(
                            new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return client;
    }

    static SimpleClientHttpRequestFactory requestFactory(OwlProperties properties) {
        if (properties.socks5Enabled() && properties.socks5Host().isBlank()) {
            throw new IllegalArgumentException("SOCKS5 proxy host is required when SOCKS5 is enabled");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (properties.socks5Enabled()) {
            Proxy socks5 = new Proxy(
                    Proxy.Type.SOCKS, new InetSocketAddress(properties.socks5Host(), properties.socks5Port()));
            factory.setProxy(socks5);
        }
        factory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        factory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        return factory;
    }
}
