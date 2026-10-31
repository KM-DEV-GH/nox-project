package com.nox.application.port.in.caller;

import com.nox.domain.caller.CallerIdentity;

/** Returns the identity of the caller making the request (FR-016). */
public interface GetCurrentCallerUseCase {

    CallerIdentity execute(CallerIdentity caller);
}
