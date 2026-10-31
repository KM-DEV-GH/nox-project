package com.nox.adapter.in.web.security;

import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Maps validated token claims to the domain {@link CallerIdentity} (research R6). Keycloak marks
 * service-account tokens with a {@code client_id} claim and a {@code service-account-} username.
 */
public class JwtCallerIdentityMapper {

    private static final String CLIENT_ID = "client_id";
    private static final String AUTHORIZED_PARTY = "azp";
    private static final String USERNAME = "preferred_username";
    private static final String EMAIL = "email";
    private static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    private final KeycloakRoleExtractor roleExtractor;

    public JwtCallerIdentityMapper(KeycloakRoleExtractor roleExtractor) {
        this.roleExtractor = roleExtractor;
    }

    /**
     * @throws InvalidBearerTokenException when the token lacks the claims that identify a caller,
     *         so the request fails as unauthenticated rather than with a server error
     */
    public CallerIdentity map(Jwt jwt) {
        boolean service = isServiceAccount(jwt);
        try {
            return new CallerIdentity(
                    jwt.getSubject(),
                    service ? CallerType.SERVICE : CallerType.USER,
                    service ? null : jwt.getClaimAsString(USERNAME),
                    service ? clientIdOf(jwt) : null,
                    jwt.getClaimAsString(EMAIL),
                    roleExtractor.extract(jwt.getClaims()));
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Token does not identify a caller", e);
        }
    }

    private static boolean isServiceAccount(Jwt jwt) {
        String username = jwt.getClaimAsString(USERNAME);
        return jwt.hasClaim(CLIENT_ID) || (username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX));
    }

    private static String clientIdOf(Jwt jwt) {
        String clientId = jwt.getClaimAsString(CLIENT_ID);
        return clientId != null ? clientId : jwt.getClaimAsString(AUTHORIZED_PARTY);
    }
}
