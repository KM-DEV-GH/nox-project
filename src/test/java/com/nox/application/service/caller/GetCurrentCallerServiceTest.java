package com.nox.application.service.caller;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GetCurrentCallerServiceTest {

    @Test
    void returnsTheGivenCallerUnchanged() {
        CallerIdentity caller =
                new CallerIdentity("sub-1", CallerType.USER, "alice", null, "alice@example.test", Set.of("nox-tester"));

        assertThat(new GetCurrentCallerService().execute(caller)).isSameAs(caller);
    }
}
