package com.nox.domain.caller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallerIdentityTest {

    @Test
    void createsUserIdentity() {
        CallerIdentity caller =
                new CallerIdentity("sub-1", CallerType.USER, "alice", null, "alice@example.test", Set.of("nox-tester"));

        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.clientId()).isNull();
        assertThat(caller.email()).isEqualTo("alice@example.test");
    }

    @Test
    void createsServiceIdentityWithoutEmail() {
        CallerIdentity caller =
                new CallerIdentity("sub-2", CallerType.SERVICE, null, "nox-test-client", null, Set.of());

        assertThat(caller.clientId()).isEqualTo("nox-test-client");
        assertThat(caller.username()).isNull();
        assertThat(caller.email()).isNull();
    }

    @Test
    void requiresNonBlankSubjectId() {
        assertThatThrownBy(() -> new CallerIdentity(" ", CallerType.USER, "alice", null, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallerIdentity(null, CallerType.USER, "alice", null, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresCallerType() {
        assertThatThrownBy(() -> new CallerIdentity("sub", null, "alice", null, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userMustHaveUsernameAndNoClientId() {
        assertThatThrownBy(() -> new CallerIdentity("sub", CallerType.USER, null, null, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallerIdentity("sub", CallerType.USER, "alice", "client", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceMustHaveClientIdAndNoUsername() {
        assertThatThrownBy(() -> new CallerIdentity("sub", CallerType.SERVICE, null, null, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallerIdentity("sub", CallerType.SERVICE, "svc", "client", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesAreSortedDeduplicatedAndUnmodifiable() {
        Set<String> roles = new HashSet<>(List.of("b-role", "a-role", "c-role"));
        CallerIdentity caller = new CallerIdentity("sub", CallerType.USER, "alice", null, null, roles);
        roles.add("z-role");

        assertThat(caller.roles()).containsExactly("a-role", "b-role", "c-role");
        assertThatThrownBy(() -> caller.roles().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullRolesBecomeEmpty() {
        assertThat(new CallerIdentity("sub", CallerType.USER, "alice", null, null, null).roles())
                .isEmpty();
    }
}
