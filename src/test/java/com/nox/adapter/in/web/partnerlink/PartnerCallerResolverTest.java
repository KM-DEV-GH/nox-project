package com.nox.adapter.in.web.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nox.adapter.in.web.security.CallerAuthentication;
import com.nox.application.port.in.caller.FindCallablePartnerUseCase;
import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class PartnerCallerResolverTest {

    private static final Partner PARTNER = new Partner(
            UUID.randomUUID(),
            "demo-partner",
            "Demo partner",
            null,
            PartnerStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"),
            null);

    private final FindCallablePartnerUseCase findCallablePartner =
            clientId -> "demo-partner".equals(clientId) ? Optional.of(PARTNER) : Optional.empty();
    private final PartnerCallerResolver resolver = new PartnerCallerResolver(findCallablePartner);

    @Test
    void resolvesTheCallablePartnerForABffCaller() {
        HttpServletRequest request = requestWithServiceId("demo-partner");

        assertThat(resolver.resolve(bff(), request)).contains(PARTNER);
    }

    @Test
    void doesNotResolveAnUnknownOrNonCallablePartner() {
        assertThat(resolver.resolve(bff(), requestWithServiceId("unknown-partner")))
                .isEmpty();
        assertThat(resolver.resolve(bff(), requestWithServiceId(null))).isEmpty();
    }

    @Test
    void neverReadsTheHeaderForAServiceWithoutTheBffRole() {
        HttpServletRequest request = requestWithServiceId("demo-partner");

        assertThat(resolver.resolve(service(Set.of()), request)).isEmpty();
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void neverReadsTheHeaderForAUserEvenWithTheBffRole() {
        HttpServletRequest request = requestWithServiceId("demo-partner");

        assertThat(resolver.resolve(user(Set.of(PartnerCallerResolver.BFF_ROLE)), request))
                .isEmpty();
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void neverReadsTheHeaderWithoutAuthentication() {
        HttpServletRequest request = requestWithServiceId("demo-partner");

        assertThat(resolver.resolve(null, request)).isEmpty();
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void grantsAccessAndExposesThePartnerToTheController() {
        HttpServletRequest request = requestWithServiceId("demo-partner");

        boolean granted = resolver.authorize(this::bff, new RequestAuthorizationContext(request))
                .isGranted();

        assertThat(granted).isTrue();
        verify(request).setAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE, PARTNER);
    }

    @Test
    void deniesAccessWhenNoPartnerResolves() {
        HttpServletRequest request = requestWithServiceId("unknown-partner");

        assertThat(resolver.authorize(this::bff, new RequestAuthorizationContext(request))
                        .isGranted())
                .isFalse();
    }

    private Authentication bff() {
        return service(Set.of(PartnerCallerResolver.BFF_ROLE));
    }

    private static Authentication service(Set<String> roles) {
        return authentication(new CallerIdentity("sub-bff", CallerType.SERVICE, null, "nox-bff", null, roles));
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

    private static HttpServletRequest requestWithServiceId(String serviceId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(PartnerCallerResolver.SERVICE_ID_HEADER)).thenReturn(serviceId);
        return request;
    }
}
