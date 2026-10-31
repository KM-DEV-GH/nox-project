package com.nox.infrastructure.config;

import com.nox.adapter.in.web.partnerlink.InternalCallerResolver;
import com.nox.adapter.in.web.partnerlink.PartnerCallerResolver;
import com.nox.adapter.in.web.security.BearerFailureHandler;
import com.nox.adapter.in.web.security.CallerAuthenticationConverter;
import com.nox.adapter.in.web.security.JwtCallerIdentityMapper;
import com.nox.adapter.in.web.security.KeycloakRoleExtractor;
import com.nox.adapter.in.web.security.ProblemAccessDeniedHandler;
import com.nox.adapter.in.web.security.ProblemAuthenticationEntryPoint;
import com.nox.adapter.in.web.security.SingleBearerTokenResolver;
import com.nox.application.port.in.caller.FindCallablePartnerUseCase;
import jakarta.servlet.Filter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Headless bearer-token API security (constitution Principle II): stateless, deny by default, and
 * only the {@code GET /healthz} and {@code GET /readyz} probes are public, because orchestrators
 * cannot obtain tokens.
 *
 * <p>Partner-facing endpoints accept only the partner BFF ({@code partner-access}) calling for a
 * callable partner; user partner link endpoints accept only the internal BFF
 * ({@code internal-access}). A user's own token reaches neither (004 research.md D1).
 *
 * <p>Where generated API docs are enabled (the {@code dev} profile only), they and Swagger UI are
 * public too: a browser cannot attach a Bearer token to the UI's own requests. Calls made from the
 * UI still need a token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PROBES = {"/healthz", "/readyz"};
    private static final String[] PARTNER_FACING = {"/api/v1/partner-link-requests/**", "/api/v1/partner-links/**"};
    /** Partner payments live under a user's link but are partner-facing; matched before the user routes. */
    private static final String[] PARTNER_PAYMENTS = {
        "/api/v1/user-partner-links/*/payments/**", "/api/v1/user-partner-links/by-external-id/*/payments/**"
    };
    private static final String[] USER_PARTNER_LINKS = {"/api/v1/user-partner-links/**"};
    private static final String[] API_DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/docs", "/swagger-ui/**"};

    @Bean
    KeycloakRoleExtractor keycloakRoleExtractor(AuthProperties properties) {
        return new KeycloakRoleExtractor(properties.audience());
    }

    @Bean
    CallerAuthenticationConverter callerAuthenticationConverter(KeycloakRoleExtractor roleExtractor) {
        return new CallerAuthenticationConverter(new JwtCallerIdentityMapper(roleExtractor));
    }

    @Bean
    PartnerCallerResolver partnerCallerResolver(FindCallablePartnerUseCase findCallablePartner) {
        return new PartnerCallerResolver(findCallablePartner);
    }

    @Bean
    InternalCallerResolver internalCallerResolver() {
        return new InternalCallerResolver();
    }

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            CallerAuthenticationConverter authenticationConverter,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler,
            BearerFailureHandler bearerFailureHandler,
            SingleBearerTokenResolver bearerTokenResolver,
            PartnerCallerResolver partnerCallerResolver,
            InternalCallerResolver internalCallerResolver,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers ->
                        headers.cacheControl(cacheControl -> {}).contentTypeOptions(contentTypeOptions -> {}))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, PUBLIC_PROBES)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, apiDocsEnabled ? API_DOCS : new String[0])
                        .permitAll()
                        .requestMatchers(PARTNER_FACING)
                        .access(partnerCallerResolver)
                        .requestMatchers(PARTNER_PAYMENTS)
                        .access(partnerCallerResolver)
                        .requestMatchers(USER_PARTNER_LINKS)
                        .access(internalCallerResolver)
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .withObjectPostProcessor(failureHandler(bearerFailureHandler)));
        return withoutPublicMetadataEndpoint(http.build());
    }

    private static ObjectPostProcessor<BearerTokenAuthenticationFilter> failureHandler(BearerFailureHandler handler) {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                filter.setAuthenticationFailureHandler(handler);
                return filter;
            }
        };
    }

    /**
     * Spring Security always serves OAuth 2.0 protected resource metadata without authentication.
     * Only the probes may be public, so that filter is removed from the chain.
     */
    private static SecurityFilterChain withoutPublicMetadataEndpoint(DefaultSecurityFilterChain chain) {
        List<Filter> filters = chain.getFilters().stream()
                .filter(filter -> !(filter instanceof OAuth2ProtectedResourceMetadataFilter))
                .toList();
        return new DefaultSecurityFilterChain(chain.getRequestMatcher(), filters);
    }
}
