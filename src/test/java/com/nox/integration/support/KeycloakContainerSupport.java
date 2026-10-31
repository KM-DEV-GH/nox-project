package com.nox.integration.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base class for integration tests against one shared, real Keycloak container (research R11, R12),
 * plus the shared {@link InfrastructureContainers}.
 *
 * <p>The Keycloak container is started once per JVM and imports the test realm with credentials
 * generated at runtime. Tests talk to the application over HTTP through {@link #client} and can move time
 * with {@link #clock}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({KeycloakContainerSupport.ClockOverride.class, TestOnlyPingController.class, TestOnlyRoleController.class})
public abstract class KeycloakContainerSupport {

    protected static final TestCredentials CREDENTIALS = TestCredentials.generate();
    protected static final KeycloakContainer KEYCLOAK = NoxKeycloakContainer.create(CREDENTIALS);
    protected static final TestTokens TOKENS;

    static {
        KEYCLOAK.start();
        TOKENS = new TestTokens(KEYCLOAK, CREDENTIALS);
    }

    protected RestTestClient client;
    protected MutableClock clock;

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("nox.auth.issuer-uri", () -> NoxKeycloakContainer.issuer(KEYCLOAK));
        registry.add("nox.auth.jwks-uri", () -> NoxKeycloakContainer.jwksUri(KEYCLOAK));
        registry.add("nox.auth.audience", () -> NoxKeycloakContainer.AUDIENCE);
        InfrastructureContainers.properties().forEach(registry::add);
    }

    @BeforeEach
    void bindCollaborators(@Autowired RestTestClient client, @Autowired MutableClock clock) {
        this.client = client;
        this.clock = clock;
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
