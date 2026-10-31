package com.nox.adapter.in.web.partnerlink;

import java.util.UUID;

/** Contract {@code LinkRequestAcceptedResponse.data}: always {@code ACCEPTED}, whatever the email. */
public record LinkRequestAcceptedResponse(UUID requestId, String status) {

    private static final String ACCEPTED = "ACCEPTED";

    static LinkRequestAcceptedResponse of(UUID requestId) {
        return new LinkRequestAcceptedResponse(requestId, ACCEPTED);
    }
}
