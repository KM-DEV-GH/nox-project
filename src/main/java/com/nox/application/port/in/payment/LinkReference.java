package com.nox.application.port.in.payment;

import java.util.UUID;

/** How the partner names the user's link: NOX's id, or its own reference (research.md D9). */
public sealed interface LinkReference {

    /** NOX's {@code userLinkId}. */
    record ById(UUID userLinkId) implements LinkReference {}

    /** The partner's {@code partner_external_id}, resolved only within that partner's links. */
    record ByExternalId(String externalLinkId) implements LinkReference {}
}
