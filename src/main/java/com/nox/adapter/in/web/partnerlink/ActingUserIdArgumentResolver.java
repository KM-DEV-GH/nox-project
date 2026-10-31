package com.nox.adapter.in.web.partnerlink;

import com.nox.adapter.in.web.error.ProblemException;
import com.nox.adapter.in.web.error.ProblemType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link ActingUser} parameters from {@code X-User-ID} (004 research.md D3). Runs only
 * after {@link InternalCallerResolver} has admitted the caller, so the header can narrow a granted
 * request to a {@code bad_request} but never widen access (FR-006, FR-007).
 */
public class ActingUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_ID_HEADER = "X-User-ID";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingUser.class) && parameter.getParameterType() == UUID.class;
    }

    @Override
    public UUID resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new ProblemException(ProblemType.BAD_REQUEST);
        }
        return parseCanonical(userId).orElseThrow(() -> new ProblemException(ProblemType.BAD_REQUEST));
    }

    /** {@link UUID#fromString} also accepts short forms such as {@code 1-1-1-1-1}; only the canonical form is accepted. */
    private static Optional<UUID> parseCanonical(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
