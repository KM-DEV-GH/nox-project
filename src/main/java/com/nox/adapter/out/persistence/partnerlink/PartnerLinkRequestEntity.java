package com.nox.adapter.out.persistence.partnerlink;

import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapping of {@code partner_link_request}. */
@Entity
@Table(name = "partner_link_request")
public class PartnerLinkRequestEntity {

    @Id
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "partner_external_id", nullable = false)
    private String partnerExternalId;

    @Column(name = "licensee_id", nullable = false)
    private String licenseeId;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "lookup_status", nullable = false)
    private LookupStatus lookupStatus;

    @Column(name = "link_id")
    private UUID linkId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PartnerLinkRequestEntity() {}

    static PartnerLinkRequestEntity of(PartnerLinkRequest request) {
        PartnerLinkRequestEntity entity = new PartnerLinkRequestEntity();
        entity.id = request.id();
        entity.partnerId = request.partnerId();
        entity.idempotencyKey = request.idempotencyKey();
        entity.partnerExternalId = request.partnerExternalId();
        entity.licenseeId = request.licenseeId();
        entity.email = request.email();
        entity.lookupStatus = request.lookupStatus();
        entity.linkId = request.linkId();
        entity.correlationId = request.correlationId();
        entity.submittedAt = request.submittedAt();
        entity.expiresAt = request.expiresAt();
        return entity;
    }

    PartnerLinkRequest toDomain() {
        return PartnerLinkRequest.restore(
                id,
                partnerId,
                idempotencyKey,
                partnerExternalId,
                licenseeId,
                email,
                lookupStatus,
                linkId,
                correlationId,
                submittedAt,
                expiresAt);
    }
}
