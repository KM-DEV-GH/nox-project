package com.nox.adapter.out.http;

import java.net.InetSocketAddress;
import java.net.Proxy;
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
 * Builds the {@link RestClient} every outbound adapter calls through. Calls are bounded by connect and
 * read timeouts, routed through the SOCKS5 proxy when enabled, and authenticated with NOX's own
 * client-credentials token: fetched, cached and refreshed by Spring Security, and dropped when the
 * remote service answers 401. Calls run outside any user's request, so every one uses the same service
 * principal, and the partner's token is never forwarded.
 */
public final class HttpClients {

    private static final Authentication NOX =
            new AnonymousAuthenticationToken("nox", "nox", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private HttpClients() {}

    /** A builder with the base URL, bounded connections and the bearer token; the caller adds anything else. */
    public static RestClient.Builder restClientBuilder(
            String baseUrl, HttpClientSettings settings, ClientRegistration registration) {
        SimpleClientHttpRequestFactory requestFactory = requestFactory(settings);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(accessToken(registration, requestFactory));
    }

    /** Shared by API and token requests, so the proxy and the timeouts apply to both. */
    static SimpleClientHttpRequestFactory requestFactory(HttpClientSettings settings) {
        if (settings.socks5Enabled() && settings.socks5Host().isBlank()) {
            throw new IllegalArgumentException("SOCKS5 proxy host is required when SOCKS5 is enabled");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (settings.socks5Enabled()) {
            factory.setProxy(
                    new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(settings.socks5Host(), settings.socks5Port())));
        }
        factory.setConnectTimeout(Math.toIntExact(settings.connectTimeout().toMillis()));
        factory.setReadTimeout(Math.toIntExact(settings.readTimeout().toMillis()));
        return factory;
    }

    private static OAuth2ClientHttpRequestInterceptor accessToken(
            ClientRegistration registration, SimpleClientHttpRequestFactory requestFactory) {
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        OAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);
        RestClientClientCredentialsTokenResponseClient tokenClient =
                new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
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
