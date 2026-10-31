package com.nox.adapter.out.persistence.partnerlink;

import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Mapping of {@code partner_link}; {@link #version} gives optimistic locking (research.md decision 4). */
@Entity
@Table(name = "partner_link")
public class PartnerLinkEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "user_keycloak_id", nullable = false)
    private String userKeycloakId;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "partner_external_id", nullable = false)
    private String partnerExternalId;

    @Column(name = "licensee_id", nullable = false)
    private String licenseeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnerLinkStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PartnerLinkEntity() {}

    static PartnerLinkEntity of(PartnerLink link) {
        PartnerLinkEntity entity = new PartnerLinkEntity();
        entity.id = link.id();
        entity.userId = link.userId();
        entity.userKeycloakId = link.userKeycloakId();
        entity.partnerId = link.partnerId();
        entity.partnerExternalId = link.partnerExternalId();
        entity.licenseeId = link.licenseeId();
        entity.status = link.status();
        entity.expiresAt = link.expiresAt();
        entity.version = link.version();
        entity.createdAt = link.createdAt();
        entity.updatedAt = link.updatedAt();
        return entity;
    }

    PartnerLink toDomain() {
        return PartnerLink.restore(
                id,
                userId,
                userKeycloakId,
                partnerId,
                partnerExternalId,
                licenseeId,
                status,
                expiresAt,
                version,
                createdAt,
                updatedAt);
    }
}
