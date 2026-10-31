package com.nox.integration.support;

import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.domain.caller.CallerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only protected endpoints. Registered only by importing this {@link TestConfiguration}, so
 * they never ship in the application.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestOnlyPingController.Endpoints.class)
public class TestOnlyPingController {

    public static final String PING = "/api/v1/test/ping";
    public static final String BOOM = "/api/v1/test/boom";

    @RestController
    static class Endpoints {

        private final MetaFactory metaFactory;

        Endpoints(MetaFactory metaFactory) {
            this.metaFactory = metaFactory;
        }

        @GetMapping(PING)
        ApiResponse<String> ping(@AuthenticationPrincipal CallerIdentity caller, HttpServletRequest request) {
            return new ApiResponse<>("pong", metaFactory.create(request));
        }

        @GetMapping(BOOM)
        ApiResponse<String> boom() {
            throw new IllegalStateException("test-only failure with internal details");
        }
    }
}
