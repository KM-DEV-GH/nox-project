package com.nox.adapter.in.web.partnerlink;

import com.nox.adapter.in.web.security.ServiceCallerAuthorization;
import com.nox.application.port.in.caller.FindCallablePartnerUseCase;
import com.nox.domain.partner.Partner;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Guards the partner-facing endpoints (research.md decision 6). {@code x-service-id} is a client
 * assertion, so it is read only for a SERVICE caller holding the {@code partner-access} role, and
 * only a partner that may call now is accepted. The resolved partner is handed to the controller as
 * the request attribute {@link #PARTNER_ATTRIBUTE}.
 */
public class PartnerCallerResolver implements AuthorizationManager<RequestAuthorizationContext> {

    public static final String SERVICE_ID_HEADER = "x-service-id";
    public static final String BFF_ROLE = "partner-access";
    public static final String PARTNER_ATTRIBUTE = "com.nox.adapter.in.web.partnerlink.callingPartner";

    private final FindCallablePartnerUseCase findCallablePartner;

    public PartnerCallerResolver(FindCallablePartnerUseCase findCallablePartner) {
        this.findCallablePartner = findCallablePartner;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        Optional<Partner> partner = resolve(authentication.get(), request);
        partner.ifPresent(callable -> request.setAttribute(PARTNER_ATTRIBUTE, callable));
        return new AuthorizationDecision(partner.isPresent());
    }

    /** The partner the BFF is calling for; empty for any other caller, without reading the header. */
    public Optional<Partner> resolve(Authentication authentication, HttpServletRequest request) {
        if (!ServiceCallerAuthorization.isServiceWithRole(authentication, BFF_ROLE)) {
            return Optional.empty();
        }
        String serviceId = request.getHeader(SERVICE_ID_HEADER);
        if (serviceId == null || serviceId.isBlank()) {
            return Optional.empty();
        }
        return findCallablePartner.findCallable(serviceId);
    }
}
