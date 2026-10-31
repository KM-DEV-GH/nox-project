package com.nox.adapter.in.web.partnerlink;

import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.UUID;

/** Contract {@code PartnerLinkData}: the partner's view of an established link. */
public record PartnerLinkResponse(UUID linkId, String externalId, PartnerLinkStatus status) {

    static PartnerLinkResponse of(PartnerLink link) {
        return new PartnerLinkResponse(link.id(), link.partnerExternalId(), link.status());
    }
}
