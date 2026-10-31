package com.nox.adapter.out.identityprovider;

/** Lifecycle of the realm's signing key set held in memory (data-model.md, SigningKeySet). */
public enum SigningKeyState {
    /** Never fetched successfully since startup. */
    NOT_LOADED,
    /** Fetched at least once; the latest fetch succeeded. */
    LOADED,
    /** Fetched at least once; the latest fetch failed, so cached keys are used. */
    LOADED_STALE
}
