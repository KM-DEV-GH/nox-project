package com.nox.application.service.partnerlink;

import static com.nox.application.service.partnerlink.UserDecisionFixture.CREATED;
import static com.nox.application.service.partnerlink.UserDecisionFixture.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.domain.partnerlink.ActorType;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

class GetMyPartnerLinkHistoryServiceTest {

    private final UserDecisionFixture fixture = new UserDecisionFixture();
    private final GetMyPartnerLinkHistoryService service =
            new GetMyPartnerLinkHistoryService(fixture.links, fixture.history);

    @Test
    void returnsTheEntriesOfTheUsersOwnLinkOldestFirst() {
        PartnerLink link = fixture.link(PartnerLinkStatus.REVOKED);
        PartnerLinkHistoryEntry revoked = entry(
                link.id(), PartnerLinkStatus.CONNECTED, PartnerLinkStatus.REVOKED, TransitionReason.USER_REVOKED, 120);
        PartnerLinkHistoryEntry created =
                entry(link.id(), null, PartnerLinkStatus.PENDING, TransitionReason.LINK_CREATED, 0);
        PartnerLinkHistoryEntry approved = entry(
                link.id(), PartnerLinkStatus.PENDING, PartnerLinkStatus.CONNECTED, TransitionReason.USER_APPROVED, 60);
        fixture.history.append(revoked);
        fixture.history.append(created);
        fixture.history.append(approved);
        fixture.history.append(
                entry(UUID.randomUUID(), null, PartnerLinkStatus.PENDING, TransitionReason.LINK_CREATED, 30));

        assertThat(service.history(link.id(), USER_ID))
                .get()
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(created, approved, revoked);
    }

    @Test
    void anotherUsersLinkIsNotFound() {
        PartnerLink link = fixture.link(PartnerLinkStatus.CONNECTED);

        assertThat(service.history(link.id(), "user-mallory")).isEmpty();
        assertThat(service.history(UUID.randomUUID(), USER_ID)).isEmpty();
    }

    private static PartnerLinkHistoryEntry entry(
            UUID linkId,
            PartnerLinkStatus from,
            PartnerLinkStatus to,
            TransitionReason reason,
            long secondsAfterCreation) {
        return new PartnerLinkHistoryEntry(
                UUID.randomUUID(),
                linkId,
                from,
                to,
                ActorType.USER,
                "user-alice",
                CREATED.plusSeconds(secondsAfterCreation),
                reason);
    }
}
