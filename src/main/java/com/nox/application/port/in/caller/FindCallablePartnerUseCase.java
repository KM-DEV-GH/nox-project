package com.nox.application.port.in.caller;

import com.nox.domain.partner.Partner;
import java.util.Optional;

/** Resolves the partner a BFF call is made for; empty unless that partner may call now. */
public interface FindCallablePartnerUseCase {

    Optional<Partner> findCallable(String clientId);
}
