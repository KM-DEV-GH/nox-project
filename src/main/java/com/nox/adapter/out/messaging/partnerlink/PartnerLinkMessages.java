package com.nox.adapter.out.messaging.partnerlink;

import com.nox.application.event.PartnerLinkApprovalRequested;
import com.nox.application.event.PartnerLinkApprovedForPartner;
import com.nox.application.event.PartnerLinkApprovedForUser;
import com.nox.application.event.PartnerLinkEvent;
import com.nox.application.event.PartnerLinkRejectedForPartner;
import com.nox.application.event.PartnerLinkRejectedForUser;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.time.Instant;
import java.util.UUID;

/** Exchanges, routing keys and payloads of the published messages (contracts/events.md). */
public final class PartnerLinkMessages {

    public static final String EVENTS_EXCHANGE = "partnerlink.events";
    public static final String WEBHOOKS_EXCHANGE = "partnerlink.webhooks";
    public static final String APPROVAL_REQUESTED = "partner-link.approval-requested.v1";
    public static final String APPROVED = "partner-link.approved.v1";
    public static final String REJECTED = "partner-link.rejected.v1";

    private PartnerLinkMessages() {}

    public static OutboundMessage toMessage(PartnerLinkEvent event) {
        return new OutboundMessage(event.messageId(), event.traceId(), event.correlationId(), dataOf(event));
    }

    private static Object dataOf(PartnerLinkEvent event) {
        return switch (event) {
            case PartnerLinkApprovalRequested requested ->
                new ApprovalRequestedData(
                        requested.linkId(),
                        requested.userId(),
                        requested.partnerName(),
                        requested.licenseeId(),
                        requested.expiresAt());
            case PartnerLinkApprovedForUser approved ->
                new UserDecisionData(
                        approved.linkId(), approved.userId(), approved.partnerName(), approved.occurredAt());
            case PartnerLinkRejectedForUser rejected ->
                new UserDecisionData(
                        rejected.linkId(), rejected.userId(), rejected.partnerName(), rejected.occurredAt());
            case PartnerLinkApprovedForPartner approved ->
                new PartnerApprovedData(
                        approved.partnerClientId(),
                        approved.requestId(),
                        approved.externalId(),
                        approved.linkId(),
                        PartnerLinkStatus.CONNECTED.name(),
                        approved.occurredAt());
            case PartnerLinkRejectedForPartner rejected ->
                new PartnerRejectedData(
                        rejected.partnerClientId(),
                        rejected.requestId(),
                        rejected.externalId(),
                        PartnerLinkStatus.REJECTED.name(),
                        rejected.occurredAt());
            default ->
                throw new IllegalArgumentException(
                        "Not a published event: " + event.getClass().getSimpleName());
        };
    }

    record ApprovalRequestedData(
            UUID linkId, String userId, String partnerName, String licenseeId, Instant expiresAt) {}

    record UserDecisionData(UUID linkId, String userId, String partnerName, Instant occurredAt) {}

    record PartnerApprovedData(
            String partnerClientId,
            UUID requestId,
            String externalId,
            UUID linkId,
            String status,
            Instant occurredAt) {}

    record PartnerRejectedData(
            String partnerClientId, UUID requestId, String externalId, String status, Instant occurredAt) {}
}
