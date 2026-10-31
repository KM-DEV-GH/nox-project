package com.nox.application.service.partnerlink;

import static com.nox.application.service.partnerlink.UserDecisionFixture.CREATED;
import static com.nox.application.service.partnerlink.UserDecisionFixture.DEADLINE;
import static com.nox.application.service.partnerlink.UserDecisionFixture.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.port.in.partnerlink.UserPartnerLink;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkRequestStatus;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PartnerLinkQueryServicesTest {

    private static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(1);

    private final UserDecisionFixture fixture = new UserDecisionFixture();

    @Test
    void requestStatusFollowsTheReportedStatusTable() {
        PartnerLinkRequest unresolved = PartnerLinkRequest.submit(
                UUID.randomUUID(),
                fixture.partner.id(),
                "key-2",
                "external-2",
                "licensee-1",
                "nobody@example.test",
                UUID.randomUUID(),
                CREATED,
                Duration.ofHours(72));
        unresolved.markNotFound();
        fixture.requests.save(unresolved);
        PartnerLink rejected = fixture.link(PartnerLinkStatus.REJECTED);

        assertThat(requestStatusAt(BEFORE_DEADLINE).status(fixture.partner.id(), unresolved.id()))
                .contains(PartnerLinkRequestStatus.PROCESSING);
        assertThat(requestStatusAt(DEADLINE).status(fixture.partner.id(), unresolved.id()))
                .contains(PartnerLinkRequestStatus.EXPIRED);
        assertThat(requestStatusAt(BEFORE_DEADLINE).status(fixture.partner.id(), fixture.request.id()))
                .contains(PartnerLinkRequestStatus.REJECTED);
        assertThat(rejected.id()).isEqualTo(fixture.request.linkId());
    }

    @Test
    void aPendingLinksRequestIsProcessingThenExpired() {
        fixture.link(PartnerLinkStatus.PENDING);

        assertThat(requestStatusAt(BEFORE_DEADLINE).status(fixture.partner.id(), fixture.request.id()))
                .contains(PartnerLinkRequestStatus.PROCESSING);
        assertThat(requestStatusAt(DEADLINE).status(fixture.partner.id(), fixture.request.id()))
                .contains(PartnerLinkRequestStatus.EXPIRED);
    }

    @Test
    void anotherPartnersRequestIsNotFound() {
        fixture.link(PartnerLinkStatus.PENDING);

        assertThat(requestStatusAt(BEFORE_DEADLINE).status(UUID.randomUUID(), fixture.request.id()))
                .isEmpty();
        assertThat(requestStatusAt(BEFORE_DEADLINE).status(fixture.partner.id(), UUID.randomUUID()))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PartnerLinkStatus.class,
            names = {"CONNECTED", "REVOKED"})
    void partnersReadEstablishedLinksOfTheirOwn(PartnerLinkStatus status) {
        PartnerLink link = fixture.link(status);
        GetPartnerLinkForPartnerService service = new GetPartnerLinkForPartnerService(fixture.links);

        assertThat(service.byId(fixture.partner.id(), link.id())).isPresent();
        assertThat(service.byExternalId(fixture.partner.id(), "external-1")).isPresent();
        assertThat(service.byId(UUID.randomUUID(), link.id())).isEmpty();
        assertThat(service.byExternalId(UUID.randomUUID(), "external-1")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PartnerLinkStatus.class,
            names = {"PENDING", "REJECTED", "EXPIRED"})
    void partnersNeverReadLinksThatAreNotEstablished(PartnerLinkStatus status) {
        PartnerLink link = fixture.link(status);
        GetPartnerLinkForPartnerService service = new GetPartnerLinkForPartnerService(fixture.links);

        assertThat(service.byId(fixture.partner.id(), link.id())).isEmpty();
        assertThat(service.byExternalId(fixture.partner.id(), "external-1")).isEmpty();
    }

    @Test
    void byExternalIdReturnsTheMostRecentlyCreatedEstablishedLink() {
        PartnerLink older = store(PartnerLinkStatus.REVOKED, CREATED);
        PartnerLink newer = store(PartnerLinkStatus.CONNECTED, CREATED.plusSeconds(60));
        store(PartnerLinkStatus.PENDING, CREATED.plusSeconds(120));
        GetPartnerLinkForPartnerService service = new GetPartnerLinkForPartnerService(fixture.links);

        assertThat(service.byExternalId(fixture.partner.id(), "external-9"))
                .get()
                .extracting(PartnerLink::id)
                .isEqualTo(newer.id());
        assertThat(older.id()).isNotEqualTo(newer.id());
    }

    @Test
    void usersListAndReadOnlyTheirOwnLinksWithEffectiveStatuses() {
        PartnerLink pending = fixture.link(PartnerLinkStatus.PENDING);
        PartnerLink foreign = PartnerLink.restore(
                UUID.randomUUID(),
                "user-bob",
                fixture.partner.id(),
                "external-1",
                "licensee-1",
                PartnerLinkStatus.CONNECTED,
                DEADLINE,
                0L,
                CREATED,
                CREATED);
        fixture.links.put(foreign);
        Clock afterDeadline = Clock.fixed(DEADLINE, ZoneOffset.UTC);

        assertThat(new ListMyPartnerLinksService(fixture.links, fixture.partners, afterDeadline).list(USER_ID))
                .singleElement()
                .satisfies(link -> {
                    assertThat(link.linkId()).isEqualTo(pending.id());
                    assertThat(link.status()).isEqualTo(PartnerLinkStatus.EXPIRED);
                    assertThat(link.partnerName()).isEqualTo(fixture.partner.name());
                    assertThat(link.partnerWebsite()).isEqualTo(fixture.partner.website());
                    assertThat(link.expiresAt()).isEqualTo(DEADLINE);
                    assertThat(link.createdAt()).isEqualTo(CREATED);
                });
        GetMyPartnerLinkService get = new GetMyPartnerLinkService(fixture.links, fixture.partners, afterDeadline);
        assertThat(get.get(pending.id(), USER_ID))
                .get()
                .extracting(UserPartnerLink::status)
                .isEqualTo(PartnerLinkStatus.EXPIRED);
        assertThat(get.get(foreign.id(), USER_ID)).isEmpty();
        assertThat(fixture.links.get(pending.id()).status()).isEqualTo(PartnerLinkStatus.PENDING);
    }

    private GetPartnerLinkRequestStatusService requestStatusAt(Instant now) {
        return new GetPartnerLinkRequestStatusService(
                fixture.requests, fixture.links, Clock.fixed(now, ZoneOffset.UTC));
    }

    private PartnerLink store(PartnerLinkStatus status, Instant createdAt) {
        PartnerLink link = PartnerLink.restore(
                UUID.randomUUID(),
                "user-" + UUID.randomUUID(),
                fixture.partner.id(),
                "external-9",
                "licensee-1",
                status,
                DEADLINE,
                0L,
                createdAt,
                createdAt);
        fixture.links.put(link);
        return link;
    }
}
