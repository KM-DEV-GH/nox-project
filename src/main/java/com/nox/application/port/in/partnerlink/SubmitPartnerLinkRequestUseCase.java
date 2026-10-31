package com.nox.application.port.in.partnerlink;

import java.util.UUID;

/**
 * A partner asks to link one of its users (spec FR-001, FR-004, FR-005). Stores the request and
 * answers at once; the user is looked up later, so the answer never depends on the email.
 */
public interface SubmitPartnerLinkRequestUseCase {

    /** The {@code requestId}: a new one, or the original for a repeated idempotency key. */
    UUID submit(Command command);

    /**
     * @param partnerId the calling partner
     * @param idempotencyKey unique per partner; a repeat returns the original request
     * @param externalId the partner's own reference for the user
     * @param email the email to look the user up by
     * @param licenseeId the licensee the link is for
     */
    record Command(UUID partnerId, String idempotencyKey, String externalId, String email, String licenseeId) {}
}
