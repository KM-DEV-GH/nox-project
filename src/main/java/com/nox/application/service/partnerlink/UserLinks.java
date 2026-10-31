package com.nox.application.service.partnerlink;

import com.nox.application.port.in.partnerlink.UserPartnerLink;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.PartnerLink;
import java.time.Instant;

/** Builds the user's view of a link. */
final class UserLinks {

    private UserLinks() {}

    static UserPartnerLink view(PartnerLink link, Partner partner, Instant now) {
        return new UserPartnerLink(
                link.id(),
                partner.name(),
                partner.website(),
                link.effectiveStatus(now),
                link.expiresAt(),
                link.createdAt());
    }
}
