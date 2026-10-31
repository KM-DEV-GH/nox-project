package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.NoxKeycloakContainer;
import com.nox.integration.support.TestOnlyPingController;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;

/** SC-007, FR-013: tokens signed with a newly rotated key are accepted without a restart. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class KeyRotationIT extends KeycloakContainerSupport {

    private String rotatedKeyProviderId;

    @AfterEach
    void removeRotatedKey() {
        if (rotatedKeyProviderId != null) {
            realm().components().component(rotatedKeyProviderId).remove();
        }
    }

    @Test
    void acceptsTokensSignedWithRotatedKey() throws Exception {
        String before = TOKENS.user("alice");
        assertPingOk(before);

        rotatedKeyProviderId = addHigherPriorityKey();
        String after = TOKENS.user("alice");

        assertThat(keyId(after)).isNotEqualTo(keyId(before));
        assertPingOk(after);
        assertPingOk(before);
    }

    private void assertPingOk(String token) {
        client.get()
                .uri(TestOnlyPingController.PING)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private String addHigherPriorityKey() {
        ComponentRepresentation key = new ComponentRepresentation();
        key.setName("rotated-rsa-" + System.nanoTime());
        key.setProviderId("rsa-generated");
        key.setProviderType("org.keycloak.keys.KeyProvider");
        key.setParentId(realm().toRepresentation().getId());
        MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
        config.put("priority", List.of("1000"));
        config.put("keySize", List.of("2048"));
        config.put("algorithm", List.of("RS256"));
        config.put("active", List.of("true"));
        config.put("enabled", List.of("true"));
        key.setConfig(config);
        try (Response response = realm().components().add(key)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static RealmResource realm() {
        return KEYCLOAK.getKeycloakAdminClient().realm(NoxKeycloakContainer.REALM);
    }

    private static String keyId(String token) throws Exception {
        return SignedJWT.parse(token).getHeader().getKeyID();
    }
}
