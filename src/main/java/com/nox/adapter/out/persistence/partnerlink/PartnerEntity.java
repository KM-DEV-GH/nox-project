package com.nox.adapter.out.persistence.partnerlink;

import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Read-only mapping of {@code partner}; partners are provisioned out of band. */
@Entity
@Table(name = "partner")
public class PartnerEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String name;

    private String website;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnerStatus status;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PartnerEntity() {}

    Partner toDomain() {
        return new Partner(id, clientId, name, website, status, validFrom, validTo);
    }
}
