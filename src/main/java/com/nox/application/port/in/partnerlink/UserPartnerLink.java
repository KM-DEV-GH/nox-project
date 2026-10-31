package com.nox.application.port.in.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A link as its user sees it.
 *
 * @param linkId the link
 * @param partnerName the partner's display name
 * @param partnerWebsite the partner's website; {@code null} when it has none
 * @param status the effective status: an undecided link past its deadline is {@code EXPIRED}
 * @param expiresAt the approval deadline
 * @param createdAt when the link was created
 */
public record UserPartnerLink(
        UUID linkId,
        String partnerName,
        String partnerWebsite,
        PartnerLinkStatus status,
        Instant expiresAt,
        Instant createdAt) {}
