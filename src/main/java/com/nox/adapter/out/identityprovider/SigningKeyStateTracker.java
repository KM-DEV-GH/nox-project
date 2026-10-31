package com.nox.adapter.out.identityprovider;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Thread-safe record of the signing key state. Ready latches on the first successful fetch: there
 * is no transition back to {@link SigningKeyState#NOT_LOADED} (FR-017).
 */
@Component
public class SigningKeyStateTracker {

    private final AtomicReference<SigningKeyState> state = new AtomicReference<>(SigningKeyState.NOT_LOADED);

    public void recordSuccess() {
        state.set(SigningKeyState.LOADED);
    }

    public void recordFailure() {
        state.updateAndGet(current ->
                current == SigningKeyState.NOT_LOADED ? SigningKeyState.NOT_LOADED : SigningKeyState.LOADED_STALE);
    }

    public SigningKeyState state() {
        return state.get();
    }

    public boolean isReady() {
        return state() != SigningKeyState.NOT_LOADED;
    }
}
