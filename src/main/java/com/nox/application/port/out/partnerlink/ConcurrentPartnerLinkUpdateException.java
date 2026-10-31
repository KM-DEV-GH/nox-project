package com.nox.application.port.out.partnerlink;

import java.util.UUID;

/** Another transaction changed the link first (optimistic locking, research.md decision 4). */
public class ConcurrentPartnerLinkUpdateException extends RuntimeException {

    public ConcurrentPartnerLinkUpdateException(UUID linkId) {
        super("Partner link " + linkId + " was changed concurrently");
    }
}
