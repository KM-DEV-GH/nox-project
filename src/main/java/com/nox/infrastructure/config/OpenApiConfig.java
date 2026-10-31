package com.nox.infrastructure.config;

import com.nox.adapter.in.web.partnerlink.ActingUser;
import com.nox.adapter.in.web.partnerlink.ActingUserIdArgumentResolver;
import com.nox.adapter.in.web.partnerlink.InternalCallerResolver;
import com.nox.adapter.in.web.partnerlink.PartnerCallerResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestAttribute;

/**
 * Generated OpenAPI docs for local development (the {@code dev} profile only). Every endpoint
 * expects a Bearer token, so Swagger UI offers an "Authorize" button for one. The reviewed contracts
 * remain the files under {@code specs/*}{@code /contracts/}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("springdoc.api-docs.enabled")
public class OpenApiConfig {

    private static final String BEARER = "bearer";

    static {
        // Resolved from X-User-ID, documented as that header below rather than as a query parameter.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(ActingUser.class);
    }

    @Bean
    OpenAPI noxOpenApi(@Value("${nox.service.version}") String version) {
        return new OpenAPI()
                .info(new Info().title("Nox API").version(version))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme(BEARER)
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * {@code x-service-id} is read by {@link PartnerCallerResolver} in the security filter chain, not
     * bound to a controller parameter, so springdoc cannot see it. Document it on every operation
     * that takes the partner the resolver hands over.
     */
    @Bean
    OperationCustomizer partnerServiceIdHeader() {
        return (operation, handlerMethod) -> {
            boolean needsPartner = Arrays.stream(handlerMethod.getMethodParameters())
                    .map(parameter -> parameter.getParameterAnnotation(RequestAttribute.class))
                    .anyMatch(attribute ->
                            attribute != null && PartnerCallerResolver.PARTNER_ATTRIBUTE.equals(attribute.value()));
            if (needsPartner) {
                operation.addParametersItem(new HeaderParameter()
                        .name(PartnerCallerResolver.SERVICE_ID_HEADER)
                        .required(true)
                        .description("Client id of the partner the BFF is calling for. Read only from callers"
                                + " holding the `" + PartnerCallerResolver.BFF_ROLE + "` role.")
                        .schema(new StringSchema()));
            }
            return operation;
        };
    }

    /** {@code X-User-ID} is bound by {@link ActingUserIdArgumentResolver}, which springdoc cannot see. */
    @Bean
    OperationCustomizer actingUserHeader() {
        return (operation, handlerMethod) -> {
            boolean actsForUser = Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(parameter -> parameter.hasParameterAnnotation(ActingUser.class));
            if (actsForUser) {
                operation.addParametersItem(new HeaderParameter()
                        .name(ActingUserIdArgumentResolver.USER_ID_HEADER)
                        .required(true)
                        .description("Internal user id (UUID) of the user the internal BFF is calling for. Read"
                                + " only from callers holding the `" + InternalCallerResolver.INTERNAL_ACCESS_ROLE
                                + "` role.")
                        .schema(new StringSchema().format("uuid")));
            }
            return operation;
        };
    }
}
