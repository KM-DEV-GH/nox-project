package com.nox.integration.support;

import static org.awaitility.Awaitility.await;

import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import java.time.Duration;
import java.util.UUID;

/** Creates links through the real flow: a BFF submission, the Owl lookup, and the asynchronous resolution. */
public class LinkScenarios {

    private static final Duration ASYNC = Duration.ofSeconds(15);

    private final PartnerLinkApi api;
    private final OwlWireMockSupport owl;
    private final PartnerLinkRequestRepository requests;

    public LinkScenarios(PartnerLinkApi api, OwlWireMockSupport owl, PartnerLinkRequestRepository requests) {
        this.api = api;
        this.owl = owl;
        this.requests = requests;
    }

    /** A {@code PENDING} link between {@code partner} and a new Owl user whose Keycloak id is {@code keycloakId}. */
    public Link pendingLink(Partner partner, String keycloakId) {
        return pendingLink(partner, keycloakId, "external-" + UUID.randomUUID());
    }

    /** As {@link #pendingLink(Partner, String)}, with the partner's own reference for the user. */
    public Link pendingLink(Partner partner, String keycloakId, String externalId) {
        return pendingLink(partner, keycloakId, externalId, UUID.randomUUID().toString());
    }

    /**
     * As {@link #pendingLink(Partner, String, String)}, for the Owl user {@code userId}, so that one
     * user can hold links with several partners.
     */
    public Link pendingLink(Partner partner, String keycloakId, String externalId, String userId) {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.userFound(email, userId, keycloakId);
        return submitAndResolve(partner, email, userId, externalId);
    }

    /** Submits for an email Owl does not know. */
    public PartnerLinkRequest unknownEmailRequest(Partner partner) {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.noUser(email);
        return awaitLookup(api.submit(partner, email));
    }

    public PartnerLinkRequest awaitLookup(UUID requestId) {
        await().atMost(ASYNC)
                .until(() -> requests.findById(requestId)
                        .map(request -> request.lookupStatus() != LookupStatus.PENDING)
                        .orElse(false));
        return requests.findById(requestId).orElseThrow();
    }

    private Link submitAndResolve(Partner partner, String email, String userId, String externalId) {
        UUID requestId =
                PartnerLinkApi.requestIdOf(api.submit(partner, UUID.randomUUID().toString(), externalId, email));
        PartnerLinkRequest request = awaitLookup(requestId);
        return new Link(requestId, request.linkId(), userId, email, externalId, request.correlationId());
    }

    /**
     * @param requestId the request that resolved to the link
     * @param linkId the link, or {@code null} when the lookup found no user
     * @param userId Owl {@code user_id}
     * @param email the email submitted
     * @param externalId the partner's reference submitted with the request
     * @param correlationId the request's correlation id
     */
    public record Link(
            UUID requestId, UUID linkId, String userId, String email, String externalId, UUID correlationId) {

        public String path() {
            return PartnerLinkApi.USER_LINKS + "/" + linkId;
        }
    }
}
