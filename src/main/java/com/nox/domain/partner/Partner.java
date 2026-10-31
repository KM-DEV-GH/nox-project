package com.nox.domain.partner;

import java.time.Instant;
import java.util.UUID;

/**
 * A partner integration: an external platform that links its users to NOX. Provisioned out of band;
 * this feature only reads it.
 *
 * @param id stable internal identity referenced by links and requests
 * @param clientId the value the BFF sends as {@code x-service-id}
 * @param name display name
 * @param website optional
 * @param status lifecycle status
 * @param validFrom start of validity (inclusive)
 * @param validTo end of validity (exclusive); {@code null} while still valid
 */
public record Partner(
        UUID id,
        String clientId,
        String name,
        String website,
        PartnerStatus status,
        Instant validFrom,
        Instant validTo) {

    /** {@code true} only for an {@link PartnerStatus#ACTIVE} partner within {@code [validFrom, validTo)}. */
    public boolean isCallableAt(Instant now) {
        return status == PartnerStatus.ACTIVE && !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo));
    }
}
