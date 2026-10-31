package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.NoxKeycloakContainer;
import com.nox.integration.support.TestOnlyPingController;
import com.nox.integration.support.TestOnlyRoleController;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.http.HttpHeaders;

/** US2 scenarios 1–4: endpoints restricted by Keycloak realm and client roles. */
class RoleAuthorizationIT extends KeycloakContainerSupport {

    private static final String TESTER_ROLE = "nox-tester";

    static Stream<Arguments> accessMatrix() {
        Supplier<String> alice = () -> TOKENS.user("alice");
        Supplier<String> bob = () -> TOKENS.user("bob");
        Supplier<String> service = TOKENS::service;
        return Stream.of(
                Arguments.of("bob", bob, TestOnlyRoleController.TESTER_ONLY, 403),
                Arguments.of("bob", bob, TestOnlyRoleController.ADMIN_ONLY, 403),
                Arguments.of("bob", bob, TestOnlyPingController.PING, 200),
                Arguments.of("alice", alice, TestOnlyRoleController.TESTER_ONLY, 200),
                Arguments.of("alice", alice, TestOnlyRoleController.ADMIN_ONLY, 200),
                Arguments.of("alice", alice, TestOnlyPingController.PING, 200),
                Arguments.of("service", service, TestOnlyRoleController.TESTER_ONLY, 403),
                Arguments.of("service", service, TestOnlyRoleController.ADMIN_ONLY, 403),
                Arguments.of("service", service, TestOnlyPingController.PING, 200));
    }

    @ParameterizedTest(name = "{0} {2} -> {3}")
    @MethodSource("accessMatrix")
    void enforcesRoles(String caller, Supplier<String> token, String path, int expectedStatus) {
        var result = client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
                .exchange()
                .expectBody(String.class)
                .returnResult();

        if (expectedStatus == 403) {
            assertProblem(result, 403, "forbidden", path);
        } else {
            assertThat(result.getStatus().value()).isEqualTo(expectedStatus);
        }
    }

    @AfterEach
    void restoreDaveRole() {
        UserResource dave = dave();
        if (dave.roles().realmLevel().listEffective().stream()
                .noneMatch(r -> r.getName().equals(TESTER_ROLE))) {
            dave.roles().realmLevel().add(List.of(testerRole()));
        }
    }

    @Test
    void tokenIssuedAfterRoleRemovalIsRefused() {
        client.get()
                .uri(TestOnlyRoleController.TESTER_ONLY)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.user("dave"))
                .exchange()
                .expectStatus()
                .isOk();

        dave().roles().realmLevel().remove(List.of(testerRole()));

        var result = client.get()
                .uri(TestOnlyRoleController.TESTER_ONLY)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.user("dave"))
                .exchange()
                .expectBody(String.class)
                .returnResult();
        assertProblem(result, 403, "forbidden", TestOnlyRoleController.TESTER_ONLY);
    }

    private static RealmResource realm() {
        return KEYCLOAK.getKeycloakAdminClient().realm(NoxKeycloakContainer.REALM);
    }

    private static UserResource dave() {
        String id = realm().users().searchByUsername("dave", true).getFirst().getId();
        return realm().users().get(id);
    }

    private static RoleRepresentation testerRole() {
        return realm().roles().get(TESTER_ROLE).toRepresentation();
    }
}
