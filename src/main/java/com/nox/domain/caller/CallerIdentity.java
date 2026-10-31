package com.nox.domain.caller;

import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The trusted description of who is calling, built only from a validated access token (FR-008,
 * FR-009).
 *
 * @param subjectId stable unique ID of the user or service account
 * @param callerType person or service client
 * @param username set for {@link CallerType#USER}, otherwise {@code null}
 * @param clientId set for {@link CallerType#SERVICE}, otherwise {@code null}
 * @param email optional; {@code null} when absent, never invented
 * @param roles sorted, deduplicated, possibly empty role names
 */
public record CallerIdentity(
        String subjectId, CallerType callerType, String username, String clientId, String email, Set<String> roles) {

    public CallerIdentity {
        require(subjectId != null && !subjectId.isBlank(), "subjectId must not be blank");
        require(callerType != null, "callerType is required");
        require(
                callerType != CallerType.USER || (username != null && clientId == null),
                "a USER has a username and no clientId");
        require(
                callerType != CallerType.SERVICE || (clientId != null && username == null),
                "a SERVICE has a clientId and no username");
        roles = roles == null ? Collections.emptySortedSet() : sortedCopy(roles);
    }

    private static SortedSet<String> sortedCopy(Set<String> roles) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(roles));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
