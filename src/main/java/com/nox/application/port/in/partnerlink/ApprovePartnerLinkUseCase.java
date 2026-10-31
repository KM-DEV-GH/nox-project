package com.nox.application.port.in.partnerlink;

import java.util.Optional;
import java.util.UUID;

/** The user approves a pending link before its deadline (spec FR-007, FR-017). */
public interface ApprovePartnerLinkUseCase {

    /**
     * @param userId the internal (Owl) user id; only that user's links are found
     * @return the outcome, never an error; empty when the user has no such link
     */
    Optional<LinkDecision> approve(UUID linkId, String userId);
}
