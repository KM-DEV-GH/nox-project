package com.nox.adapter.out.identityprovider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SigningKeyStateTrackerTest {

    private final SigningKeyStateTracker tracker = new SigningKeyStateTracker();

    @Test
    void startsNotLoadedAndNotReady() {
        assertThat(tracker.state()).isEqualTo(SigningKeyState.NOT_LOADED);
        assertThat(tracker.isReady()).isFalse();
    }

    @Test
    void failureBeforeFirstLoadStaysNotLoaded() {
        tracker.recordFailure();

        assertThat(tracker.state()).isEqualTo(SigningKeyState.NOT_LOADED);
        assertThat(tracker.isReady()).isFalse();
    }

    @Test
    void firstSuccessLoads() {
        tracker.recordSuccess();

        assertThat(tracker.state()).isEqualTo(SigningKeyState.LOADED);
        assertThat(tracker.isReady()).isTrue();
    }

    @Test
    void refreshFailureAfterLoadIsStaleButReady() {
        tracker.recordSuccess();
        tracker.recordFailure();

        assertThat(tracker.state()).isEqualTo(SigningKeyState.LOADED_STALE);
        assertThat(tracker.isReady()).isTrue();
    }

    @Test
    void refreshSuccessAfterStaleIsLoadedAgain() {
        tracker.recordSuccess();
        tracker.recordFailure();
        tracker.recordSuccess();

        assertThat(tracker.state()).isEqualTo(SigningKeyState.LOADED);
    }

    @Test
    void neverReturnsToNotLoaded() {
        tracker.recordSuccess();
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure();
        }

        assertThat(tracker.state()).isNotEqualTo(SigningKeyState.NOT_LOADED);
        assertThat(tracker.isReady()).isTrue();
    }
}
