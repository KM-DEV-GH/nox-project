package com.nox.adapter.in.web.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nox.domain.caller.CallerIdentity;
import java.util.Set;

/** Contract {@code CallerIdentity}: absent values are serialized as explicit {@code null}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallerIdentityResponse(
        String subjectId, String callerType, String username, String clientId, String email, Set<String> roles) {

    static CallerIdentityResponse of(CallerIdentity caller) {
        return new CallerIdentityResponse(
                caller.subjectId(),
                caller.callerType().name(),
                caller.username(),
                caller.clientId(),
                caller.email(),
                caller.roles());
    }
}
