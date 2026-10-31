package com.nox.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class AuthPropertiesTest {

    private static final String ISSUER = "nox.auth.issuer-uri=http://localhost:8180/realms/nox-test";
    private static final String JWKS =
            "nox.auth.jwks-uri=http://localhost:8180/realms/nox-test/protocol/openid-connect/certs";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(AuthPropertiesConfig.class);

    @Test
    void bindsAllPropertiesWithDefaults() {
        runner.withPropertyValues(ISSUER, JWKS).run(context -> {
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertThat(properties.issuerUri()).isEqualTo("http://localhost:8180/realms/nox-test");
            assertThat(properties.jwksUri())
                    .isEqualTo("http://localhost:8180/realms/nox-test/protocol/openid-connect/certs");
            assertThat(properties.audience()).isEqualTo("nox-api");
            assertThat(properties.jwksConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.jwksReadTimeout()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void bindsExplicitValues() {
        runner.withPropertyValues(
                        ISSUER,
                        JWKS,
                        "nox.auth.audience=other-api",
                        "nox.auth.jwks-connect-timeout=500ms",
                        "nox.auth.jwks-read-timeout=3s")
                .run(context -> {
                    AuthProperties properties = context.getBean(AuthProperties.class);
                    assertThat(properties.audience()).isEqualTo("other-api");
                    assertThat(properties.jwksConnectTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(properties.jwksReadTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void rejectsZeroConnectTimeout() {
        runner.withPropertyValues(ISSUER, JWKS, "nox.auth.jwks-connect-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNegativeReadTimeout() {
        runner.withPropertyValues(ISSUER, JWKS, "nox.auth.jwks-read-timeout=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsBlankIssuer() {
        runner.withPropertyValues("nox.auth.issuer-uri= ", JWKS)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodProfileFailsToStartWithoutIssuerAndJwksUri() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(
                        AuthPropertiesConfig.class, ValidationAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("prod");

        assertThatThrownBy(application::run).rootCause().hasMessageContaining("nox.auth");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class AuthPropertiesConfig {}
}
