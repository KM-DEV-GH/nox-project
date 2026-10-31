package com.nox.application.service.caller;

import com.nox.application.port.in.caller.GetCurrentCallerUseCase;
import com.nox.domain.caller.CallerIdentity;
import org.springframework.stereotype.Service;

@Service
public class GetCurrentCallerService implements GetCurrentCallerUseCase {

    @Override
    public CallerIdentity execute(CallerIdentity caller) {
        return caller;
    }
}
