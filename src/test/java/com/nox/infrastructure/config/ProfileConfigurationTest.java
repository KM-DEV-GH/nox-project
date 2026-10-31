package com.nox.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** The three environments (constitution: Deployment & Environment Configuration). */
class ProfileConfigurationTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Pattern SENSITIVE_ENTRY =
            Pattern.compile("^\\s*([\\w.-]*(?:password|secret|token)[\\w.-]*)\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    @Test
    void devLoadsWithLocalKeycloakDefaults() {
        try (ConfigurableApplicationContext context = start("dev")) {
            AuthProperties auth = context.getBean(AuthProperties.class);
            assertThat(auth.issuerUri()).isEqualTo("http://localhost:8180/realms/nox-dev");
            assertThat(auth.jwksUri()).isEqualTo("http://localhost:8180/realms/nox-dev/protocol/openid-connect/certs");
            assertThat(auth.audience()).isEqualTo("nox-api");
            assertThat(context.getEnvironment().getProperty("nox.service.environment"))
                    .isEqualTo("dev");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage", "prod"})
    void failsFastWithoutAuthEnvironmentVariables(String profile) {
        assertThatThrownBy(() -> start(profile).close()).rootCause().hasMessageContaining("nox.auth");
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage", "prod"})
    void startsWhenAuthEnvironmentIsProvided(String profile) {
        try (ConfigurableApplicationContext context = start(
                profile,
                "--nox.auth.issuer-uri=https://idp.example.test/realms/nox",
                "--nox.auth.jwks-uri=https://idp.example.test/realms/nox/protocol/openid-connect/certs")) {
            assertThat(context.getEnvironment().getProperty("nox.service.environment"))
                    .isEqualTo(profile);
        }
    }

    @Test
    void noProfileFileContainsALiteralSecret() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(RESOURCES)) {
            files = paths.filter(path -> path.getFileName().toString().matches("application.*\\.ya?ml"))
                    .toList();
        }
        assertThat(files).hasSize(4);

        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                Matcher entry = SENSITIVE_ENTRY.matcher(line);
                if (entry.matches()) {
                    assertThat(entry.group(2).strip())
                            .as("%s in %s must be empty or an environment placeholder", entry.group(1), file)
                            .matches("|\\$\\{[A-Z0-9_]+(:)?}");
                }
            }
        }
    }

    private static ConfigurableApplicationContext start(String profile, String... args) {
        return new SpringApplicationBuilder(AuthPropertiesOnly.class, ValidationAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class AuthPropertiesOnly {}
}
