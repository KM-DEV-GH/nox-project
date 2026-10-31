package com.nox.adapter.in.web.partnerlink;

import com.nox.adapter.in.web.security.ServiceCallerAuthorization;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Guards the user partner link endpoints (004 research.md D1, D2): only a SERVICE caller holding
 * {@code internal-access}. The decision is made from the token alone; the acted-for user's
 * {@code X-User-ID} is read later, by {@link ActingUserIdArgumentResolver}, and never here.
 */
public class InternalCallerResolver implements AuthorizationManager<RequestAuthorizationContext> {

    public static final String INTERNAL_ACCESS_ROLE = "internal-access";

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(
                ServiceCallerAuthorization.isServiceWithRole(authentication.get(), INTERNAL_ACCESS_ROLE));
    }
}
