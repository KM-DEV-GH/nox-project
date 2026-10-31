package com.nox.application.service.health;

import com.nox.application.port.in.health.GetServiceHealthUseCase;
import com.nox.application.port.out.health.DependencyHealthProbe;
import com.nox.domain.health.ServiceHealth;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GetServiceHealthService implements GetServiceHealthUseCase {

    private final List<DependencyHealthProbe> probes;

    public GetServiceHealthService(List<DependencyHealthProbe> probes) {
        this.probes = List.copyOf(probes);
    }

    @Override
    public ServiceHealth execute() {
        return ServiceHealth.aggregate(
                probes.stream().map(DependencyHealthProbe::check).toList());
    }
}
