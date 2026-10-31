package com.nox.application.service.payment;

import com.nox.application.port.in.payment.LinkReference;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the link a payment request names, within the authenticated partner only (research.md
 * D9): the same established links a partner may read, and for an external reference the most
 * recently created of them (spec FR-017).
 */
final class PaymentLinks {

    private static final Set<PartnerLinkStatus> ESTABLISHED =
            EnumSet.of(PartnerLinkStatus.CONNECTED, PartnerLinkStatus.REVOKED);

    private final PartnerLinkRepository links;

    PaymentLinks(PartnerLinkRepository links) {
        this.links = links;
    }

    /** @throws PaymentException {@code LINK_NOT_FOUND} for any link the partner may not see */
    PartnerLink resolve(UUID partnerId, LinkReference reference) {
        Optional<PartnerLink> link = switch (reference) {
            case LinkReference.ById byId ->
                links.findById(byId.userLinkId())
                        .filter(found -> found.partnerId().equals(partnerId))
                        .filter(PartnerLink::isEstablished);
            case LinkReference.ByExternalId byExternalId ->
                links.findLatestByPartnerAndExternalId(partnerId, byExternalId.externalLinkId(), ESTABLISHED);
        };
        return link.orElseThrow(() -> new PaymentException(PaymentFailure.LINK_NOT_FOUND));
    }

    /** @throws PaymentException {@code LINK_NOT_CONNECTED} unless the user is still connected */
    static void requireConnected(PartnerLink link) {
        if (link.status() != PartnerLinkStatus.CONNECTED) {
            throw new PaymentException(PaymentFailure.LINK_NOT_CONNECTED);
        }
    }
}
