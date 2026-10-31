package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.KeycloakContainerSupport;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Probes answer without a token (FR-017, contract {@code Health}). */
class ProbesIT extends KeycloakContainerSupport {

    private final BuildProperties buildProperties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    ProbesIT(@Autowired BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @BeforeEach
    void waitUntilKeysAreLoaded() {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> client.get()
                                .uri("/readyz")
                                .exchange()
                                .returnResult()
                                .getStatus()
                                .value()
                        == 200);
    }

    @Test
    void readinessIsOkWithEmptyBody() {
        client.get()
                .uri("/readyz")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .isEmpty();
    }

    @Test
    void healthMatchesContract() {
        String body = client.get()
                .uri("/healthz")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        JsonNode health = jsonMapper.readTree(body);
        ContractAssertions.assertMatchesSchema(health, "Health");
        assertThat(health.get("status").asString()).isEqualTo("UP");
        assertThat(health.get("serviceId").asString()).isEqualTo("nox");
        assertThat(health.get("version").asString()).isNotBlank().isEqualTo(buildProperties.getVersion());
        assertThat(health.get("checks")
                        .valueStream()
                        .map(check -> check.get("componentId").asString()))
                .containsExactlyInAnyOrder("keycloak", "postgresql", "rabbitmq");
        assertCheck(health, "keycloak", "external-service");
        assertCheck(health, "postgresql", "postgresql");
        assertCheck(health, "rabbitmq", "rabbitmq");
    }

    private static void assertCheck(JsonNode health, String componentId, String componentType) {
        JsonNode check = health.get("checks")
                .valueStream()
                .filter(candidate -> candidate.get("componentId").asString().equals(componentId))
                .findFirst()
                .orElseThrow();
        assertThat(check.get("componentType").asString()).isEqualTo(componentType);
        assertThat(check.get("status").asString()).isEqualTo("UP");
        assertThat(check.get("time").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(check.has("critical")).isFalse();
    }
}
