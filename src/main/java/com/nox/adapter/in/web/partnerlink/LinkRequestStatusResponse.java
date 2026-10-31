package com.nox.adapter.in.web.partnerlink;

import com.nox.domain.partnerlink.PartnerLinkRequestStatus;
import java.util.UUID;

/** Contract {@code LinkRequestStatusResponse.data}. */
public record LinkRequestStatusResponse(UUID requestId, PartnerLinkRequestStatus status) {}
