package com.nox.integration;

import static com.nox.integration.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import com.nox.integration.support.TestTokens;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * FR-017: a decision at or after the deadline is refused with {@code link_expired} and the expiry is
 * recorded, with no messages. The test approval window is 2 minutes; the clock moves past it.
 */
class PartnerLinkExpiryIT extends PartnerLinkIntegrationSupport {

    private static final Duration PAST_DEADLINE = Duration.ofMinutes(2).plusSeconds(1);

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void aLateDecisionIsRefusedAndTheExpiryRecorded(String action) {
        String userMessages = queues.bind("partnerlink.events", "#");
        String partnerMessages = queues.bind("partnerlink.webhooks", "#");
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(TOKENS.user("alice")));
        clock.advance(PAST_DEADLINE);

        EntityExchangeResult<String> late = api.postAsInternalBff(link.userId(), link.path() + "/" + action);

        assertProblem(late, 409, "link_expired", link.path() + "/" + action);
        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.EXPIRED);
        List<PartnerLinkHistoryEntry> entries = history.findByLinkId(link.linkId());
        assertThat(entries)
                .extracting(PartnerLinkHistoryEntry::reason)
                .containsExactly(TransitionReason.LINK_CREATED, TransitionReason.EXPIRED_ON_LATE_ACTION);
        assertThat(entries.getLast().actorType()).isEqualTo(ActorType.NOX);
        assertThat(entries.getLast().actorReference()).isNull();
        assertThat(queues.collect(
                        userMessages,
                        mentioning(link.linkId()).and(message -> !isApprovalRequest(message)),
                        Duration.ofSeconds(2)))
                .isEmpty();
        assertThat(queues.collect(partnerMessages, mentioning(link.requestId()), Duration.ofSeconds(1)))
                .isEmpty();
    }

    @Test
    void aSecondLateDecisionAddsNoFurtherEntry() {
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(TOKENS.user("alice")));
        clock.advance(PAST_DEADLINE);
        api.postAsInternalBff(link.userId(), link.path() + "/approve");

        EntityExchangeResult<String> again = api.postAsInternalBff(link.userId(), link.path() + "/reject");

        assertThat(again.getStatus().value()).isEqualTo(409);
        assertThat(history.findByLinkId(link.linkId())).hasSize(2);
    }

    @Test
    void anUndecidedLinkPastItsDeadlineReadsAsExpiredWithoutAWrite() {
        Link link = scenarios.pendingLink(partner, TestTokens.subjectOf(TOKENS.user("alice")));
        clock.advance(PAST_DEADLINE);

        PartnerLink stored = links.findById(link.linkId()).orElseThrow();

        assertThat(stored.status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(stored.effectiveStatus(clock.instant())).isEqualTo(PartnerLinkStatus.EXPIRED);
        assertThat(history.findByLinkId(link.linkId())).hasSize(1);
    }

    private static boolean isApprovalRequest(Message message) {
        return "partner-link.approval-requested.v1"
                .equals(message.getMessageProperties().getType());
    }
}
