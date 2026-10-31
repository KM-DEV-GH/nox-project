package com.nox.application.port.in.partnerlink;

import java.util.Optional;
import java.util.UUID;

/** The user ends a {@code CONNECTED} link; nothing else can be revoked and nothing is published (FR-015, FR-016). */
public interface RevokePartnerLinkUseCase {

    /**
     * @param userId the internal (Owl) user id; only that user's links are found
     * @return the outcome; empty when the user has no such link
     */
    Optional<LinkDecision> revoke(UUID linkId, String userId);
}
