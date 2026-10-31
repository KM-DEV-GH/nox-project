package com.nox.application.service.partnerlink;

import static com.nox.application.service.partnerlink.UserDecisionFixture.DEADLINE;
import static com.nox.application.service.partnerlink.UserDecisionFixture.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.port.in.partnerlink.LinkDecision;
import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.LinkDecisionOutcome;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RevokePartnerLinkServiceTest {

    private static final Instant AFTER_DEADLINE = DEADLINE.plusSeconds(3600);

    private final UserDecisionFixture fixture = new UserDecisionFixture();

    @Test
    void revokesAConnectedLinkWithHistoryAndNoMessages() {
        PartnerLink link = fixture.link(PartnerLinkStatus.CONNECTED);

        LinkDecision decision =
                serviceAt(AFTER_DEADLINE).revoke(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.REVOKED);
        assertThat(decision.link().status()).isEqualTo(PartnerLinkStatus.REVOKED);
        assertThat(fixture.links.get(link.id()).status()).isEqualTo(PartnerLinkStatus.REVOKED);
        assertThat(fixture.history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.fromStatus()).isEqualTo(PartnerLinkStatus.CONNECTED);
            assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.REVOKED);
            assertThat(entry.actorType()).isEqualTo(ActorType.USER);
            assertThat(entry.actorReference()).isEqualTo(USER_ID);
            assertThat(entry.reason()).isEqualTo(TransitionReason.USER_REVOKED);
            assertThat(entry.occurredAt()).isEqualTo(AFTER_DEADLINE);
        });
        assertThat(fixture.events.events()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PartnerLinkStatus.class,
            names = {"PENDING", "REJECTED", "EXPIRED", "REVOKED"})
    void anythingElseIsNotActionable(PartnerLinkStatus status) {
        PartnerLink link = fixture.link(status);

        LinkDecision decision =
                serviceAt(DEADLINE.minusSeconds(1)).revoke(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(fixture.links.saves()).isEmpty();
        assertThat(fixture.history.entries()).isEmpty();
        assertThat(fixture.events.events()).isEmpty();
    }

    @Test
    void anotherUsersLinkIsNotFound() {
        PartnerLink link = fixture.link(PartnerLinkStatus.CONNECTED);

        assertThat(serviceAt(AFTER_DEADLINE).revoke(link.id(), "user-mallory")).isEmpty();
    }

    private RevokePartnerLinkService serviceAt(Instant now) {
        return new RevokePartnerLinkService(fixture.deciderAt(now));
    }
}
