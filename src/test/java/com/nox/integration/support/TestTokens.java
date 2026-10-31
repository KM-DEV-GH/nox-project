package com.nox.integration.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access tokens for integration tests: real ones issued by the Keycloak test realm, and crafted
 * invalid ones signed locally with Nimbus. Tokens are returned to the caller only, never printed.
 */
public class TestTokens {

    public static final String TEST_CLIENT = "nox-test-client";
    public static final String OTHER_APP = "other-app";
    public static final String BFF = "nox-bff";
    public static final String INTERNAL_BFF = "bff";

    private static final String FOREIGN_ISSUER = "https://foreign.example.test/realms/nox-test";

    private final KeycloakContainer keycloak;
    private final TestCredentials credentials;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RSAKey localKey;

    public TestTokens(KeycloakContainer keycloak, TestCredentials credentials) {
        this.keycloak = keycloak;
        this.credentials = credentials;
        this.localKey = generateKey();
    }

    /** A user token obtained with the password grant through {@code nox-test-client}. */
    public String user(String username) {
        return requestToken(Map.of(
                "grant_type",
                "password",
                "client_id",
                TEST_CLIENT,
                "client_secret",
                credentials.noxTestClientSecret(),
                "username",
                username,
                "password",
                credentials.passwordOf(username)));
    }

    /** The subject ({@code sub}) of a token: for a user, the Keycloak id Owl reports as {@code keycloak_id}. */
    public static String subjectOf(String token) {
        return claimsOf(token).getSubject();
    }

    /** A service-account token for {@code nox-test-client} (client credentials grant). */
    public String service() {
        return clientCredentials(TEST_CLIENT, credentials.noxTestClientSecret());
    }

    /** The partner BFF's application-to-application token; its service account holds {@code partner-access}. */
    public String bff() {
        return clientCredentials(BFF, credentials.bffSecret());
    }

    /** The internal BFF's application-to-application token; its service account holds {@code internal-access}. */
    public String internalBff() {
        return clientCredentials(INTERNAL_BFF, credentials.internalBffSecret());
    }

    /** A valid realm token issued to {@code other-app}; its audience does not include {@code nox-api}. */
    public String otherApp() {
        return clientCredentials(OTHER_APP, credentials.otherAppSecret());
    }

    /** A real token whose payload was changed after signing. */
    public String tampered() {
        String[] parts = user("alice").split("\\.");
        JsonNode claims = jsonMapper.readTree(Base64URL.from(parts[1]).decodeToString());
        Map<String, Object> changed = jsonMapper.convertValue(claims, LinkedHashMap.class);
        changed.put("preferred_username", "mallory");
        String payload = Base64URL.encode(jsonMapper.writeValueAsBytes(changed)).toString();
        return parts[0] + "." + payload + "." + parts[2];
    }

    /** An unsigned token ({@code alg=none}) carrying the claims of a real token. */
    public String algNone() {
        return new PlainJWT(claimsOf(user("alice"))).serialize();
    }

    /** An RS256 token with otherwise valid claims, signed by a key of a different issuer. */
    public String foreignIssuer() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(claimsOf(user("alice")))
                .issuer(FOREIGN_ISSUER)
                .build();
        return signLocally(claims, localKey.getKeyID());
    }

    /** A token with valid claims, signed with a local key whose {@code kid} the realm does not publish. */
    public String unknownKeyId() {
        return signLocally(claimsOf(user("alice")), "unknown-" + UUID.randomUUID());
    }

    /** A token with valid claims for the configured issuer and audience, signed with an unknown key. */
    public String unknownKeyId(String issuer) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience("nox-api")
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        return signLocally(claims, "unknown-" + UUID.randomUUID());
    }

    private String clientCredentials(String clientId, String secret) {
        return requestToken(Map.of(
                "grant_type", "client_credentials",
                "client_id", clientId,
                "client_secret", secret));
    }

    private String requestToken(Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(keycloak.getTokenEndpoint(NoxKeycloakContainer.REALM)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token request failed with HTTP " + response.statusCode());
            }
            return jsonMapper.readTree(response.body()).get("access_token").asString();
        } catch (IOException e) {
            throw new IllegalStateException("Token request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Token request interrupted", e);
        }
    }

    private String signLocally(JWTClaimsSet claims, String keyId) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
            jwt.sign(new RSASSASigner(localKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet claimsOf(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
