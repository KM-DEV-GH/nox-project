package com.nox.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtValidationTest {

    private static final String ISSUER = "http://localhost:8180/realms/nox-test";
    private static final String AUDIENCE = "nox-api";
    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private static RSAKey signingKey;
    private static JwtDecoder decoder;

    @BeforeAll
    static void createDecoder() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("realm-key").generate();
        AuthProperties properties = new AuthProperties(
                ISSUER,
                ISSUER + "/protocol/openid-connect/certs",
                AUDIENCE,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        decoder = new JwtDecoderConfig()
                .jwtDecoder(
                        new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK())),
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsTokenThatSatisfiesAllChecks() {
        Jwt jwt = decoder.decode(rs256(validClaims().build()));

        assertThat(jwt.getSubject()).isEqualTo("subject-1");
    }

    @Test
    void acceptsAudienceListContainingTheServiceAudience() {
        assertThat(decoder.decode(rs256(
                        validClaims().audience(List.of("account", AUDIENCE)).build())))
                .isNotNull();
    }

    @Test
    void rejectsUnsignedToken() {
        String token = new PlainJWT(validClaims().build()).serialize();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsHmacSignedToken() throws JOSEException {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("realm-key").build(),
                validClaims().build());
        jwt.sign(new MACSigner(secret));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsOtherRsaAlgorithm() {
        String token = sign(JWSAlgorithm.RS512, validClaims().build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsIssuerThatIsNotExactlyEqual() {
        assertThatThrownBy(() ->
                        decoder.decode(rs256(validClaims().issuer(ISSUER + "/").build())))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(rs256(
                        validClaims().issuer("http://evil.test/realms/nox-test").build())))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsAudienceWithoutServiceAudience() {
        assertThatThrownBy(() ->
                        decoder.decode(rs256(validClaims().audience("other-app").build())))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(
                        rs256(validClaims().audience((String) null).build())))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsTokenExpiredMoreThanThirtySecondsAgo() {
        String token = rs256(validClaims()
                .issueTime(at(NOW.minusSeconds(400)))
                .expirationTime(at(NOW.minusSeconds(31)))
                .build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void toleratesExpiryWithinClockSkew() {
        assertThat(decoder.decode(rs256(validClaims()
                        .issueTime(at(NOW.minusSeconds(400)))
                        .expirationTime(at(NOW.minusSeconds(29)))
                        .build())))
                .isNotNull();
    }

    @Test
    void rejectsTokenNotValidForMoreThanThirtySeconds() {
        String token =
                rs256(validClaims().notBeforeTime(at(NOW.plusSeconds(31))).build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void toleratesNotBeforeWithinClockSkew() {
        assertThat(decoder.decode(rs256(
                        validClaims().notBeforeTime(at(NOW.plusSeconds(29))).build())))
                .isNotNull();
    }

    @Test
    void rejectsTokenWithoutExpiry() {
        String token = rs256(validClaims().expirationTime(null).build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsTokenThatIsNotAnAccessToken() {
        assertThatThrownBy(() ->
                        decoder.decode(rs256(validClaims().claim("typ", "ID").build())))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() ->
                        decoder.decode(rs256(validClaims().claim("typ", null).build())))
                .isInstanceOf(BadJwtException.class);
    }

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("subject-1")
                .claim("typ", "Bearer")
                .issueTime(at(NOW.minusSeconds(10)))
                .expirationTime(at(NOW.plusSeconds(290)));
    }

    private static String rs256(JWTClaimsSet claims) {
        return sign(JWSAlgorithm.RS256, claims);
    }

    private static String sign(JWSAlgorithm algorithm, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(algorithm)
                            .keyID(signingKey.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Date at(Instant instant) {
        return Date.from(instant);
    }
}
