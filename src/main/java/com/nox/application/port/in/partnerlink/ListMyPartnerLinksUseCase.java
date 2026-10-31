package com.nox.application.port.in.partnerlink;

import java.util.List;

/** Every link of the user, whatever its status (FR-014, SC-003). */
public interface ListMyPartnerLinksUseCase {

    /** @param userId the internal (Owl) user id */
    List<UserPartnerLink> list(String userId);
}
