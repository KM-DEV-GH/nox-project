package com.nox.adapter.in.web.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.adapter.in.web.error.ProblemException;
import com.nox.adapter.in.web.error.ProblemType;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class ActingUserIdArgumentResolverTest {

    private final ActingUserIdArgumentResolver resolver = new ActingUserIdArgumentResolver();

    @Test
    void supportsOnlyAnActingUserUuid() throws Exception {
        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        assertThat(resolver.supportsParameter(parameter(1))).isFalse();
        assertThat(resolver.supportsParameter(parameter(2))).isFalse();
    }

    @Test
    void resolvesTheUserIdHeader() throws Exception {
        UUID userId = UUID.randomUUID();

        assertThat(resolve(userId.toString())).isEqualTo(userId);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "not-a-uuid", "12345", "1-1-1-1-1"})
    void aMissingBlankOrMalformedUserIdIsABadRequest(String header) {
        assertThatThrownBy(() -> resolve(header))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        problem -> assertThat(problem.type()).isEqualTo(ProblemType.BAD_REQUEST));
    }

    private Object resolve(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(ActingUserIdArgumentResolver.USER_ID_HEADER, header);
        }
        return resolver.resolveArgument(parameter(0), null, new ServletWebRequest(request), null);
    }

    private static MethodParameter parameter(int index) throws NoSuchMethodException {
        Method handler = Handler.class.getDeclaredMethod("handle", UUID.class, UUID.class, String.class);
        return new MethodParameter(handler, index);
    }

    @SuppressWarnings("unused")
    private static final class Handler {

        void handle(@ActingUser UUID actingUser, UUID plainUuid, @ActingUser String notAUuid) {}
    }
}
