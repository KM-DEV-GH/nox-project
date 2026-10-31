package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.ContractAssertions;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import com.nox.integration.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** US5, FR-009, FR-010, SC-005: the user audits every written transition of their link. */
class PartnerLinkHistoryIT extends PartnerLinkIntegrationSupport {

    @Test
    void createApproveRevokeGivesThreeOrderedEntries() {
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        api.postAsInternalBff(link.userId(), link.path() + "/approve");
        api.postAsInternalBff(link.userId(), link.path() + "/revoke");

        List<JsonNode> entries = historyOf(link);

        assertThat(entries)
                .extracting(entry -> entry.get("reason").asString())
                .containsExactly("link_created", "user_approved", "user_revoked");
        assertThat(entries)
                .extracting(entry -> entry.get("actorType").asString())
                .containsExactly("PARTNER", "USER", "USER");
        assertThat(entries.get(0).get("fromStatus").isNull()).isTrue();
        assertThat(entries)
                .extracting(entry -> entry.get("toStatus").asString())
                .containsExactly("PENDING", "CONNECTED", "REVOKED");
        assertThat(entries)
                .extracting(entry -> Instant.parse(entry.get("occurredAt").asString()))
                .isSorted();
    }

    @Test
    void aLateApprovalAddsANoxEntryTimedAtTheRefusal() {
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(alice));
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        Instant refusedAround = clock.instant();
        api.postAsInternalBff(link.userId(), link.path() + "/approve");

        List<JsonNode> entries = historyOf(link);

        assertThat(entries).hasSize(2);
        JsonNode expiry = entries.getLast();
        assertThat(expiry.get("reason").asString()).isEqualTo("expired_on_late_action");
        assertThat(expiry.get("actorType").asString()).isEqualTo("NOX");
        assertThat(expiry.get("toStatus").asString()).isEqualTo("EXPIRED");
        assertThat(Instant.parse(expiry.get("occurredAt").asString()))
                .isBetween(refusedAround.minusSeconds(1), clock.instant());
    }

    @Test
    void anotherUsersHistoryIsNotFound() {
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(TOKENS.user("alice")));

        assertProblem(
                api.getAsInternalBff(UUID.randomUUID().toString(), link.path() + "/history"),
                404,
                "not_found",
                link.path() + "/history");
    }

    private List<JsonNode> historyOf(Link link) {
        JsonNode body = PartnerLinkApi.json(api.getAsInternalBff(link.userId(), link.path() + "/history"));
        ContractAssertions.assertMatchesSchema(body, "PartnerLinkHistoryResponse");
        return StreamSupport.stream(body.get("data").spliterator(), false).toList();
    }
}
