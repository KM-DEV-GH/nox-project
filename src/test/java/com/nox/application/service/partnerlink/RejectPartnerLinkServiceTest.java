package com.nox.application.service.partnerlink;

import static com.nox.application.service.partnerlink.UserDecisionFixture.DEADLINE;
import static com.nox.application.service.partnerlink.UserDecisionFixture.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.event.PartnerLinkRejectedForPartner;
import com.nox.application.event.PartnerLinkRejectedForUser;
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

class RejectPartnerLinkServiceTest {

    private static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(60);

    private final UserDecisionFixture fixture = new UserDecisionFixture();

    @Test
    void rejectsAPendingLinkAndNotifiesBothAudiences() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);

        LinkDecision decision =
                serviceAt(BEFORE_DEADLINE).reject(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.REJECTED);
        assertThat(decision.link().status()).isEqualTo(PartnerLinkStatus.REJECTED);
        assertThat(fixture.history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.REJECTED);
            assertThat(entry.actorType()).isEqualTo(ActorType.USER);
            assertThat(entry.actorReference()).isEqualTo(USER_ID);
            assertThat(entry.reason()).isEqualTo(TransitionReason.USER_REJECTED);
        });
        assertThat(fixture.events.eventsOfType(PartnerLinkRejectedForUser.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.linkId()).isEqualTo(link.id());
                    assertThat(event.userId()).isEqualTo(USER_ID);
                    assertThat(event.partnerName()).isEqualTo(fixture.partner.name());
                    assertThat(event.occurredAt()).isEqualTo(BEFORE_DEADLINE);
                });
        assertThat(fixture.events.eventsOfType(PartnerLinkRejectedForPartner.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.partnerClientId()).isEqualTo(fixture.partner.clientId());
                    assertThat(event.requestId()).isEqualTo(fixture.request.id());
                    assertThat(event.externalId()).isEqualTo("external-1");
                    assertThat(event.correlationId()).isEqualTo(fixture.request.correlationId());
                });
        assertThat(fixture.events.events()).hasSize(2);
    }

    @Test
    void aLateRejectionExpiresTheLinkWithoutMessages() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);

        LinkDecision decision =
                serviceAt(DEADLINE.plusSeconds(1)).reject(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.EXPIRED);
        assertThat(fixture.links.get(link.id()).status()).isEqualTo(PartnerLinkStatus.EXPIRED);
        assertThat(fixture.history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.actorType()).isEqualTo(ActorType.NOX);
            assertThat(entry.reason()).isEqualTo(TransitionReason.EXPIRED_ON_LATE_ACTION);
        });
        assertThat(fixture.events.events()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PartnerLinkStatus.class,
            names = {"CONNECTED", "REJECTED", "EXPIRED", "REVOKED"})
    void aDecidedLinkIsNotActionableAndUnchanged(PartnerLinkStatus status) {
        PartnerLink link = fixture.link(status);

        LinkDecision decision =
                serviceAt(BEFORE_DEADLINE).reject(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(fixture.links.saves()).isEmpty();
        assertThat(fixture.events.events()).isEmpty();
    }

    @Test
    void anotherUsersLinkIsNotFound() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);

        assertThat(serviceAt(BEFORE_DEADLINE).reject(link.id(), "user-mallory")).isEmpty();
    }

    @Test
    void aConcurrentUpdateIsRetriedOnce() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);
        fixture.links.failNextSavesWithConflict(1);

        assertThat(serviceAt(BEFORE_DEADLINE)
                        .reject(link.id(), USER_ID)
                        .orElseThrow()
                        .outcome())
                .isEqualTo(LinkDecisionOutcome.REJECTED);
        assertThat(fixture.history.entries()).hasSize(1);
    }

    private RejectPartnerLinkService serviceAt(Instant now) {
        return new RejectPartnerLinkService(fixture.deciderAt(now));
    }
}
