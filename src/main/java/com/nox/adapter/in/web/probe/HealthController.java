package com.nox.adapter.in.web.probe;

import com.nox.application.port.in.health.GetServiceHealthUseCase;
import com.nox.domain.health.HealthStatus;
import com.nox.domain.health.ServiceHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health probe: 200 for {@code UP}/{@code DEGRADED}, 503 for {@code DOWN} (research R8). */
@RestController
public class HealthController {

    private static final String SERVICE_ID = "nox";

    private final GetServiceHealthUseCase getServiceHealth;
    private final ObjectProvider<BuildProperties> buildProperties;

    public HealthController(GetServiceHealthUseCase getServiceHealth, ObjectProvider<BuildProperties> buildProperties) {
        this.getServiceHealth = getServiceHealth;
        this.buildProperties = buildProperties;
    }

    @GetMapping("/healthz")
    ResponseEntity<HealthResponse> health() {
        ServiceHealth health = getServiceHealth.execute();
        HttpStatus status = health.status() == HealthStatus.DOWN ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        BuildProperties build = buildProperties.getIfAvailable();
        return ResponseEntity.status(status)
                .body(HealthResponse.of(health, SERVICE_ID, build != null ? build.getVersion() : null));
    }
}
