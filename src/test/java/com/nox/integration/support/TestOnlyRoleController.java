package com.nox.integration.support;

import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.domain.caller.CallerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only role-restricted endpoints (US2). Registered only by importing this {@link TestConfiguration}. */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestOnlyRoleController.Endpoints.class)
public class TestOnlyRoleController {

    public static final String TESTER_ONLY = "/api/v1/test/tester-only";
    public static final String ADMIN_ONLY = "/api/v1/test/admin-only";

    @RestController
    static class Endpoints {

        private final MetaFactory metaFactory;

        Endpoints(MetaFactory metaFactory) {
            this.metaFactory = metaFactory;
        }

        @GetMapping(TESTER_ONLY)
        @PreAuthorize("hasRole('nox-tester')")
        ApiResponse<String> testerOnly(@AuthenticationPrincipal CallerIdentity caller, HttpServletRequest request) {
            return new ApiResponse<>("tester", metaFactory.create(request));
        }

        @GetMapping(ADMIN_ONLY)
        @PreAuthorize("hasRole('nox-api-admin')")
        ApiResponse<String> adminOnly(@AuthenticationPrincipal CallerIdentity caller, HttpServletRequest request) {
            return new ApiResponse<>("admin", metaFactory.create(request));
        }
    }
}
