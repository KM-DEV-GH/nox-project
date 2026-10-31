package com.nox.application.port.in.partnerlink;

import java.util.UUID;

/** Looks up the user of a submitted request and, for a real user without an active link, creates one (FR-002, FR-003). */
public interface ResolvePartnerLinkRequestUseCase {

    void resolve(UUID requestId);
}
