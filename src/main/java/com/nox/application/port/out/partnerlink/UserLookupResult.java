package com.nox.application.port.out.partnerlink;

/** Outcome of a {@link UserLookupPort} call. {@code NotFound} and {@code Failed} are never shown to partners. */
public sealed interface UserLookupResult {

    /**
     * Exactly one eligible user.
     *
     * @param userId Owl {@code user_id}
     */
    record Found(String userId) implements UserLookupResult {}

    /** No eligible user, including an ambiguous or unverified match. */
    record NotFound() implements UserLookupResult {}

    /** Owl could not answer, even after retries. */
    record Failed() implements UserLookupResult {}
}
