package com.nox.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeycloakRoleExtractorTest {

    private final KeycloakRoleExtractor extractor = new KeycloakRoleExtractor("nox-api");

    @Test
    void readsRealmRoles() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("nox-tester", "auditor")));

        assertThat(extractor.extract(claims)).containsExactly("auditor", "nox-tester");
    }

    @Test
    void readsClientRolesOfThisServiceOnly() {
        Map<String, Object> claims = Map.of(
                "resource_access",
                Map.of(
                        "nox-api", Map.of("roles", List.of("nox-api-admin")),
                        "other-app", Map.of("roles", List.of("other-admin"))));

        assertThat(extractor.extract(claims)).containsExactly("nox-api-admin");
    }

    @Test
    void mergesAndDeduplicatesRealmAndClientRoles() {
        Map<String, Object> claims = Map.of(
                "realm_access", Map.of("roles", List.of("nox-tester", "shared")),
                "resource_access", Map.of("nox-api", Map.of("roles", List.of("shared", "nox-api-admin"))));

        assertThat(extractor.extract(claims)).containsExactly("nox-api-admin", "nox-tester", "shared");
    }

    @Test
    void missingClaimsMeanNoRoles() {
        assertThat(extractor.extract(Map.of("sub", "123"))).isEmpty();
    }

    @Test
    void malformedClaimsMeanNoRoles() {
        assertThat(extractor.extract(Map.of("realm_access", "nox-tester"))).isEmpty();
        assertThat(extractor.extract(Map.of("realm_access", Map.of("roles", "nox-tester"))))
                .isEmpty();
        assertThat(extractor.extract(Map.of("resource_access", List.of("nox-api"))))
                .isEmpty();
        assertThat(extractor.extract(Map.of("resource_access", Map.of("nox-api", "nox-api-admin"))))
                .isEmpty();
        assertThat(extractor.extract(Map.of("realm_access", Map.of("roles", List.of(42, "nox-tester")))))
                .containsExactly("nox-tester");
    }
}
