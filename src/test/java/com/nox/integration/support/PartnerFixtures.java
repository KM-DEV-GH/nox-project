package com.nox.integration.support;

import com.nox.domain.partner.Partner;
import com.nox.domain.partner.PartnerStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts partners for integration tests. Partner provisioning is out of scope for the feature, so
 * rows are written directly, each with a unique {@code client_id}.
 */
public class PartnerFixtures {

    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcClient jdbc;

    public PartnerFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Partner active() {
        return insert(PartnerStatus.ACTIVE, LONG_AGO, null);
    }

    public Partner suspended() {
        return insert(PartnerStatus.SUSPENDED, LONG_AGO, null);
    }

    public Partner deregistered() {
        return insert(PartnerStatus.DEREGISTERED, LONG_AGO, null);
    }

    /** Active, but its validity ended yesterday. */
    public Partner pastValidity() {
        return insert(PartnerStatus.ACTIVE, LONG_AGO, Instant.now().minus(1, ChronoUnit.DAYS));
    }

    /** Active, but valid only from tomorrow. */
    public Partner futureValidity() {
        return insert(PartnerStatus.ACTIVE, Instant.now().plus(1, ChronoUnit.DAYS), null);
    }

    private Partner insert(PartnerStatus status, Instant validFrom, Instant validTo) {
        UUID id = UUID.randomUUID();
        String clientId = "partner-" + id;
        String name = "Partner " + id.toString().substring(0, 8);
        jdbc.sql("""
                insert into partner (id, client_id, name, website, status, valid_from, valid_to, created_at, updated_at)
                values (:id, :clientId, :name, :website, :status, :validFrom, :validTo, now(), now())
                """)
                .param("id", id)
                .param("clientId", clientId)
                .param("name", name)
                .param("website", "https://" + clientId + ".example.test")
                .param("status", status.name())
                .param("validFrom", Timestamp.from(validFrom))
                .param("validTo", validTo == null ? null : Timestamp.from(validTo))
                .update();
        return new Partner(id, clientId, name, "https://" + clientId + ".example.test", status, validFrom, validTo);
    }
}
