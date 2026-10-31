package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nox.domain.partner.Partner;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.PartnerFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Constitution Principle IX: the schema is built by Liquibase and Hibernate only validates it; the
 * database itself enforces "one active link per user and partner" and per-partner idempotency.
 */
class LiquibaseSchemaIT extends KeycloakContainerSupport {

    private static final Timestamp NOW = Timestamp.from(Instant.now());

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Environment environment;

    private Partner partner;

    @BeforeEach
    void createPartner() {
        partner = new PartnerFixtures(jdbc).active();
    }

    @Test
    void hibernateOnlyValidatesTheLiquibaseSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.sql("select count(*) from databasechangelog")
                        .query(Integer.class)
                        .single())
                .isPositive();
        assertThat(tableExists("event_publication")).isTrue();
        assertThat(tableExists("event_publication_archive")).isTrue();
        assertThat(tableExists("partner_link_stuck_publication")).isTrue();
    }

    @Test
    void rejectsASecondActiveLinkForTheSameUserAndPartner() {
        insertLink("user-1", "PENDING");

        assertThatThrownBy(() -> insertLink("user-1", "PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLink("user-1", "CONNECTED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsAnyNumberOfInactiveLinksForTheSameUserAndPartner() {
        insertLink("user-2", "CONNECTED");
        insertLink("user-2", "REJECTED");
        insertLink("user-2", "EXPIRED");
        insertLink("user-2", "REVOKED");
        insertLink("user-2", "REVOKED");

        assertThat(jdbc.sql("select count(*) from partner_link where partner_id = :partnerId")
                        .param("partnerId", partner.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);
    }

    @Test
    void rejectsAnUnknownLinkStatus() {
        assertThatThrownBy(() -> insertLink("user-3", "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsADuplicateIdempotencyKeyForTheSamePartner() {
        insertRequest(partner.id(), "key-1");

        assertThatThrownBy(() -> insertRequest(partner.id(), "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSameIdempotencyKeyForDifferentPartners() {
        Partner other = new PartnerFixtures(jdbc).active();

        insertRequest(partner.id(), "key-2");
        insertRequest(other.id(), "key-2");

        assertThat(jdbc.sql("select count(*) from partner_link_request where idempotency_key = 'key-2'")
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(2);
    }

    private void insertLink(String userId, String status) {
        jdbc.sql("""
                insert into partner_link (id, user_id, user_keycloak_id, partner_id, partner_external_id, licensee_id,
                    status, expires_at, version, created_at, updated_at)
                values (:id, :userId, :keycloakId, :partnerId, 'external-1', 'licensee-1', :status, :now, 0, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("keycloakId", "keycloak-" + userId)
                .param("partnerId", partner.id())
                .param("status", status)
                .param("now", NOW)
                .update();
    }

    private void insertRequest(UUID partnerId, String idempotencyKey) {
        jdbc.sql("""
                insert into partner_link_request (id, partner_id, idempotency_key, partner_external_id, licensee_id,
                    email, lookup_status, link_id, correlation_id, submitted_at, expires_at)
                values (:id, :partnerId, :key, 'external-1', 'licensee-1', null, 'PENDING', null, :correlationId,
                    :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("partnerId", partnerId)
                .param("key", idempotencyKey)
                .param("correlationId", UUID.randomUUID())
                .param("now", NOW)
                .update();
    }

    private boolean tableExists(String table) {
        return jdbc.sql("select count(*) from information_schema.tables where table_name = :table")
                        .param("table", table)
                        .query(Integer.class)
                        .single()
                == 1;
    }
}
