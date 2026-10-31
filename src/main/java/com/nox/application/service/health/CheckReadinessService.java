package com.nox.application.service.health;

import com.nox.application.port.in.health.CheckReadinessUseCase;
import com.nox.application.port.out.health.ReadinessCondition;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CheckReadinessService implements CheckReadinessUseCase {

    private final List<ReadinessCondition> conditions;

    public CheckReadinessService(List<ReadinessCondition> conditions) {
        this.conditions = List.copyOf(conditions);
    }

    @Override
    public boolean execute() {
        return conditions.stream().allMatch(ReadinessCondition::isReady);
    }
}
