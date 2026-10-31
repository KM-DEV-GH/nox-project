package com.nox.adapter.in.web.security;

import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The check every front-end-service surface shares (004 research.md D1): a SERVICE caller holding
 * the surface's role. A user's own token never passes, whatever roles it carries.
 */
public final class ServiceCallerAuthorization {

    private ServiceCallerAuthorization() {}

    public static boolean isServiceWithRole(Authentication authentication, String role) {
        String authority = "ROLE_" + role;
        return authentication != null
                && authentication.getPrincipal() instanceof CallerIdentity caller
                && caller.callerType() == CallerType.SERVICE
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(authority::equals);
    }
}
