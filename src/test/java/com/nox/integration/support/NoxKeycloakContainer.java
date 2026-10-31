package com.nox.integration.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.function.Supplier;
import org.testcontainers.images.PullPolicy;

/** Factory for Keycloak containers that import the test realm with runtime-generated credentials. */
public final class NoxKeycloakContainer {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.1";
    public static final String REALM = "nox-test";
    public static final String AUDIENCE = "nox-api";

    private static final int DOCKER_ATTEMPTS = 3;

    private NoxKeycloakContainer() {}

    public static KeycloakContainer create(TestCredentials credentials) {
        return new KeycloakContainer(IMAGE)
                .withImagePullPolicy(PullPolicy.defaultPolicy())
                .withStartupAttempts(DOCKER_ATTEMPTS)
                .withRealmImportFile("/keycloak/nox-test-realm.json")
                .withAdminPassword(credentials.adminPassword())
                .withEnv("NOX_TEST_CLIENT_SECRET", credentials.noxTestClientSecret())
                .withEnv("OTHER_APP_SECRET", credentials.otherAppSecret())
                .withEnv("ALICE_PASSWORD", credentials.alicePassword())
                .withEnv("BOB_PASSWORD", credentials.bobPassword())
                .withEnv("CAROL_PASSWORD", credentials.carolPassword())
                .withEnv("DAVE_PASSWORD", credentials.davePassword())
                .withEnv("NOX_BFF_SECRET", credentials.bffSecret())
                .withEnv("INTERNAL_BFF_SECRET", credentials.internalBffSecret());
    }

    /**
     * Runs a Docker API call, retrying when the connection is dropped. Podman's Docker-compatible
     * API closes idle connections that the Docker client may still try to reuse.
     */
    public static <T> T retryingDockerCall(Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < DOCKER_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    public static String issuer(KeycloakContainer keycloak) {
        return keycloak.getIssuerUrl(REALM);
    }

    public static String jwksUri(KeycloakContainer keycloak) {
        return keycloak.getJwksUri(REALM);
    }
}
