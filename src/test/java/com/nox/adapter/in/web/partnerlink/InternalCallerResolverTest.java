package com.nox.adapter.in.web.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nox.adapter.in.web.security.CallerAuthentication;
import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class InternalCallerResolverTest {

    private final InternalCallerResolver resolver = new InternalCallerResolver();

    @Test
    void grantsAServiceHoldingInternalAccess() {
        assertThat(granted(service(Set.of(InternalCallerResolver.INTERNAL_ACCESS_ROLE))))
                .isTrue();
    }

    @Test
    void deniesAServiceWithoutInternalAccess() {
        assertThat(granted(service(Set.of()))).isFalse();
        assertThat(granted(service(Set.of(PartnerCallerResolver.BFF_ROLE)))).isFalse();
    }

    @Test
    void deniesAUserEvenWithInternalAccess() {
        assertThat(granted(user(Set.of(InternalCallerResolver.INTERNAL_ACCESS_ROLE))))
                .isFalse();
    }

    @Test
    void deniesWithoutAuthentication() {
        assertThat(granted(null)).isFalse();
    }

    @Test
    void neverReadsTheRequestOrSetsAnAttribute() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        resolver.authorize(
                () -> service(Set.of(InternalCallerResolver.INTERNAL_ACCESS_ROLE)),
                new RequestAuthorizationContext(request));

        verify(request, never()).getHeader(anyString());
        verify(request, never()).setAttribute(anyString(), any());
    }

    private boolean granted(Authentication authentication) {
        return resolver.authorize(() -> authentication, new RequestAuthorizationContext(mock(HttpServletRequest.class)))
                .isGranted();
    }

    private static Authentication service(Set<String> roles) {
        return authentication(new CallerIdentity("sub-bff", CallerType.SERVICE, null, "bff", null, roles));
    }

    private static Authentication user(Set<String> roles) {
        return authentication(new CallerIdentity("sub-alice", CallerType.USER, "alice", null, null, roles));
    }

    private static Authentication authentication(CallerIdentity caller) {
        return new CallerAuthentication(
                caller,
                caller.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
    }
}
