package com.nox.application.service.partnerlink;

import static com.nox.application.service.partnerlink.UserDecisionFixture.CREATED;
import static com.nox.application.service.partnerlink.UserDecisionFixture.DEADLINE;
import static com.nox.application.service.partnerlink.UserDecisionFixture.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.event.PartnerLinkApprovedForPartner;
import com.nox.application.event.PartnerLinkApprovedForUser;
import com.nox.application.fake.FixedTraceIdSource;
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

class ApprovePartnerLinkServiceTest {

    private static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(60);

    private final UserDecisionFixture fixture = new UserDecisionFixture();

    @Test
    void approvesAPendingLinkAndNotifiesBothAudiences() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);

        LinkDecision decision =
                serviceAt(BEFORE_DEADLINE).approve(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.APPROVED);
        assertThat(decision.link().status()).isEqualTo(PartnerLinkStatus.CONNECTED);
        assertThat(decision.link().partnerName()).isEqualTo(fixture.partner.name());
        assertThat(decision.link().partnerWebsite()).isEqualTo(fixture.partner.website());
        assertThat(fixture.links.get(link.id()).status()).isEqualTo(PartnerLinkStatus.CONNECTED);
        assertThat(fixture.history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.fromStatus()).isEqualTo(PartnerLinkStatus.PENDING);
            assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.CONNECTED);
            assertThat(entry.actorType()).isEqualTo(ActorType.USER);
            assertThat(entry.actorReference()).isEqualTo(USER_ID);
            assertThat(entry.reason()).isEqualTo(TransitionReason.USER_APPROVED);
            assertThat(entry.occurredAt()).isEqualTo(BEFORE_DEADLINE);
        });
        assertThat(fixture.events.eventsOfType(PartnerLinkApprovedForUser.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.linkId()).isEqualTo(link.id());
                    assertThat(event.userId()).isEqualTo(USER_ID);
                    assertThat(event.partnerName()).isEqualTo(fixture.partner.name());
                    assertThat(event.occurredAt()).isEqualTo(BEFORE_DEADLINE);
                    assertThat(event.correlationId()).isEqualTo(fixture.request.correlationId());
                    assertThat(event.traceId()).isEqualTo(FixedTraceIdSource.TRACE_ID);
                });
        assertThat(fixture.events.eventsOfType(PartnerLinkApprovedForPartner.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.linkId()).isEqualTo(link.id());
                    assertThat(event.partnerClientId()).isEqualTo(fixture.partner.clientId());
                    assertThat(event.requestId()).isEqualTo(fixture.request.id());
                    assertThat(event.externalId()).isEqualTo("external-1");
                    assertThat(event.occurredAt()).isEqualTo(BEFORE_DEADLINE);
                    assertThat(event.correlationId()).isEqualTo(fixture.request.correlationId());
                });
        assertThat(fixture.events.events()).hasSize(2);
    }

    @Test
    void aLateApprovalExpiresTheLinkWithoutMessages() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);

        LinkDecision decision = serviceAt(DEADLINE).approve(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.EXPIRED);
        assertThat(fixture.links.get(link.id()).status()).isEqualTo(PartnerLinkStatus.EXPIRED);
        assertThat(fixture.history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.toStatus()).isEqualTo(PartnerLinkStatus.EXPIRED);
            assertThat(entry.actorType()).isEqualTo(ActorType.NOX);
            assertThat(entry.actorReference()).isNull();
            assertThat(entry.reason()).isEqualTo(TransitionReason.EXPIRED_ON_LATE_ACTION);
            assertThat(entry.occurredAt()).isEqualTo(DEADLINE);
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
                serviceAt(BEFORE_DEADLINE).approve(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(decision.link().status()).isEqualTo(status);
        assertThat(fixture.links.saves()).isEmpty();
        assertThat(fixture.history.entries()).isEmpty();
        assertThat(fixture.events.events()).isEmpty();
    }

    @Test
    void anotherUsersLinkIsNotFound() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);

        assertThat(serviceAt(BEFORE_DEADLINE).approve(link.id(), "user-mallory"))
                .isEmpty();
        assertThat(fixture.links.get(link.id()).status()).isEqualTo(PartnerLinkStatus.PENDING);
    }

    @Test
    void aConcurrentUpdateIsRetriedOnceAgainstTheRereadLink() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);
        fixture.links.failNextSavesWithConflict(1);

        LinkDecision decision =
                serviceAt(BEFORE_DEADLINE).approve(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.APPROVED);
        assertThat(fixture.links.saves()).hasSize(1);
        assertThat(fixture.history.entries()).hasSize(1);
    }

    @Test
    void aRetryAfterTheOtherWriterDecidedIsNotActionable() {
        PartnerLink link = fixture.link(PartnerLinkStatus.PENDING);
        fixture.links.interleaveConcurrentWrite(() -> {
            PartnerLink rejectedMeanwhile = fixture.links.findById(link.id()).orElseThrow();
            rejectedMeanwhile.reject(CREATED.plusSeconds(1));
            fixture.links.save(rejectedMeanwhile);
        });

        LinkDecision decision =
                serviceAt(BEFORE_DEADLINE).approve(link.id(), USER_ID).orElseThrow();

        assertThat(decision.outcome()).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(fixture.links.get(link.id()).status()).isEqualTo(PartnerLinkStatus.REJECTED);
        assertThat(fixture.history.entries()).isEmpty();
        assertThat(fixture.events.events()).isEmpty();
    }

    private ApprovePartnerLinkService serviceAt(Instant now) {
        return new ApprovePartnerLinkService(fixture.deciderAt(now));
    }
}
