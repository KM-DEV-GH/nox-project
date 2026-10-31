package com.nox.adapter.in.web.security;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Reads Keycloak role names from validated token claims (research R5): realm roles from
 * {@code realm_access.roles} plus this service's client roles from
 * {@code resource_access.<clientId>.roles}. Roles of other clients are ignored, and missing or
 * malformed claims mean "no roles".
 */
public class KeycloakRoleExtractor {

    private static final String REALM_ACCESS = "realm_access";
    private static final String RESOURCE_ACCESS = "resource_access";
    private static final String ROLES = "roles";

    private final String clientId;

    public KeycloakRoleExtractor(String clientId) {
        this.clientId = clientId;
    }

    /** Sorted, deduplicated, unmodifiable role names. */
    public Set<String> extract(Map<String, Object> claims) {
        SortedSet<String> roles = new TreeSet<>();
        addRoles(claims.get(REALM_ACCESS), roles);
        if (claims.get(RESOURCE_ACCESS) instanceof Map<?, ?> resourceAccess) {
            addRoles(resourceAccess.get(clientId), roles);
        }
        return Collections.unmodifiableSortedSet(roles);
    }

    private static void addRoles(Object access, Set<String> roles) {
        if (access instanceof Map<?, ?> accessMap && accessMap.get(ROLES) instanceof Iterable<?> names) {
            for (Object name : names) {
                if (name instanceof String role) {
                    roles.add(role);
                }
            }
        }
    }
}
