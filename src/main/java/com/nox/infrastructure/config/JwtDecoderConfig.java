package com.nox.infrastructure.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Local token verification (research R3, R4): RS256 only, exact issuer, required audience, a
 * Keycloak access token ({@code typ} claim {@code Bearer}, not an ID or refresh token), and a
 * required {@code exp} plus optional {@code nbf} with 30 seconds of clock skew.
 */
@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfig {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final String TOKEN_TYPE_CLAIM = "typ";
    private static final String ACCESS_TOKEN_TYPE = "Bearer";

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> signingKeys, AuthProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(signingKeys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(properties.issuerUri()),
                new JwtClaimValidator<String>(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE::equals),
                new JwtAudienceValidator(properties.audience())));
        return decoder;
    }
}
