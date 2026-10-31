package com.nox.adapter.in.web.partnerlink;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nox.application.port.in.partnerlink.UserPartnerLink;
import java.time.Instant;
import java.util.UUID;

/** Contract {@code UserLinkData}; {@code partnerWebsite} is an explicit {@code null} when absent. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserLinkResponse(
        UUID linkId, String partnerName, String partnerWebsite, String status, Instant expiresAt, Instant createdAt) {

    static UserLinkResponse of(UserPartnerLink link) {
        return new UserLinkResponse(
                link.linkId(),
                link.partnerName(),
                link.partnerWebsite(),
                link.status().name(),
                link.expiresAt(),
                link.createdAt());
    }
}
