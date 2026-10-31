package com.nox.application.port.in.partnerlink;

import java.util.Optional;
import java.util.UUID;

/** One of the user's links; empty for an unknown link or another user's. */
public interface GetMyPartnerLinkUseCase {

    Optional<UserPartnerLink> get(UUID linkId, String userId);
}
