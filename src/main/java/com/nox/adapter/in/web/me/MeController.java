package com.nox.adapter.in.web.me;

import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.caller.GetCurrentCallerUseCase;
import com.nox.domain.caller.CallerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated caller's own identity (FR-016). Any authenticated caller; no role required. */
@RestController
public class MeController {

    private final GetCurrentCallerUseCase getCurrentCaller;
    private final MetaFactory metaFactory;

    public MeController(GetCurrentCallerUseCase getCurrentCaller, MetaFactory metaFactory) {
        this.getCurrentCaller = getCurrentCaller;
        this.metaFactory = metaFactory;
    }

    @GetMapping("/api/v1/me")
    ApiResponse<CallerIdentityResponse> me(@AuthenticationPrincipal CallerIdentity caller, HttpServletRequest request) {
        CallerIdentityResponse identity = CallerIdentityResponse.of(getCurrentCaller.execute(caller));
        return new ApiResponse<>(identity, metaFactory.create(request));
    }
}
