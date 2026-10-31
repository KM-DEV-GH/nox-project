package com.nox.adapter.in.web.probe;

import com.nox.application.port.in.health.CheckReadinessUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readiness probe: 200 or 503, never a body (research R8). */
@RestController
public class ReadinessController {

    private final CheckReadinessUseCase checkReadiness;

    public ReadinessController(CheckReadinessUseCase checkReadiness) {
        this.checkReadiness = checkReadiness;
    }

    @GetMapping("/readyz")
    ResponseEntity<Void> readiness() {
        HttpStatus status = checkReadiness.execute() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).build();
    }
}
