package com.nox.integration.support;

import java.util.UUID;

/**
 * Credentials for the test realm, generated at runtime and held only in memory. They are passed
 * to the Keycloak container as environment variables (resolved by the realm import) and are never
 * written to disk or logs.
 */
public record TestCredentials(
        String adminPassword,
        String noxTestClientSecret,
        String otherAppSecret,
        String alicePassword,
        String bobPassword,
        String carolPassword,
        String davePassword,
        String bffSecret,
        String internalBffSecret) {

    public static TestCredentials generate() {
        return new TestCredentials(
                random(), random(), random(), random(), random(), random(), random(), random(), random());
    }

    String passwordOf(String username) {
        return switch (username) {
            case "alice" -> alicePassword;
            case "bob" -> bobPassword;
            case "carol" -> carolPassword;
            case "dave" -> davePassword;
            default -> throw new IllegalArgumentException("Unknown test user");
        };
    }

    private static String random() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
        return "TestCredentials[redacted]";
    }
}
