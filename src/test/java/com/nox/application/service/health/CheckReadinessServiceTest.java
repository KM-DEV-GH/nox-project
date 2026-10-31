package com.nox.application.service.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.port.out.health.ReadinessCondition;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CheckReadinessServiceTest {

    @Test
    void readyWhenAllConditionsAreReady() {
        assertThat(new CheckReadinessService(List.of(() -> true, () -> true)).execute())
                .isTrue();
    }

    @Test
    void notReadyWhenAnyConditionIsNotReady() {
        assertThat(new CheckReadinessService(List.of(() -> true, () -> false)).execute())
                .isFalse();
    }

    @Test
    void readyWithoutConditions() {
        assertThat(new CheckReadinessService(List.of()).execute()).isTrue();
    }

    @Test
    void passesThroughConditionChanges() {
        AtomicBoolean ready = new AtomicBoolean(false);
        ReadinessCondition condition = ready::get;
        CheckReadinessService service = new CheckReadinessService(List.of(condition));

        assertThat(service.execute()).isFalse();
        ready.set(true);
        assertThat(service.execute()).isTrue();
    }
}
