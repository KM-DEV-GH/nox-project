package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.KeycloakContainerSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** SC-001: every registered endpoint except the two probes rejects requests without a token. */
class EndpointEnumerationIT extends KeycloakContainerSupport {

    private static final Set<String> PUBLIC_PROBES = Set.of("GET /healthz", "GET /readyz");

    private final RequestMappingHandlerMapping handlerMapping;

    EndpointEnumerationIT(
            @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Test
    void everyNonProbeEndpointRequiresAToken() {
        List<String> endpoints = endpoints();
        assertThat(endpoints).isNotEmpty().containsAll(PUBLIC_PROBES);

        List<String> unprotected = new ArrayList<>();
        for (String endpoint : endpoints) {
            if (PUBLIC_PROBES.contains(endpoint)) {
                continue;
            }
            String[] parts = endpoint.split(" ", 2);
            int status = client.method(HttpMethod.valueOf(parts[0]))
                    .uri(parts[1])
                    .exchange()
                    .returnResult()
                    .getStatus()
                    .value();
            if (status != 401) {
                unprotected.add(endpoint + " -> " + status);
            }
        }

        assertThat(unprotected).isEmpty();
    }

    @Test
    void protectedResourceMetadataIsNotPublic() {
        client.get()
                .uri("/.well-known/oauth-protected-resource")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private List<String> endpoints() {
        List<String> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            Set<RequestMethod> effective = methods.isEmpty() ? Set.of(RequestMethod.values()) : methods;
            for (String pattern : info.getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "x");
                effective.stream()
                        .filter(method -> method != RequestMethod.TRACE)
                        .forEach(method -> endpoints.add(method.name() + " " + path));
            }
        }
        return endpoints;
    }
}
