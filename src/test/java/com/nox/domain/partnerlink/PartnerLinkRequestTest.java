package com.nox.domain.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every row of the "Reported status" table in data-model.md. */
class PartnerLinkRequestTest {

    private static final Instant SUBMITTED = Instant.parse("2026-10-26T09:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);
    private static final Instant DEADLINE = SUBMITTED.plus(WINDOW);

    @Test
    void submissionFixesTheDeadlineAndAwaitsLookup() {
        PartnerLinkRequest request = submitted();

        assertThat(request.expiresAt()).isEqualTo(DEADLINE);
        assertThat(request.lookupStatus()).isEqualTo(LookupStatus.PENDING);
        assertThat(request.email()).isEqualTo("user@example.test");
        assertThat(request.linkId()).isNull();
    }

    @Test
    void resolvingRecordsTheLinkAndForgetsTheEmail() {
        PartnerLinkRequest request = submitted();
        UUID linkId = UUID.randomUUID();

        request.resolve(linkId);

        assertThat(request.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(request.linkId()).isEqualTo(linkId);
        assertThat(request.email()).isNull();
    }

    @Test
    void notFoundAndFailedForgetTheEmail() {
        PartnerLinkRequest notFound = submitted();
        PartnerLinkRequest failed = submitted();

        notFound.markNotFound();
        failed.markFailed();

        assertThat(notFound.lookupStatus()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(failed.lookupStatus()).isEqualTo(LookupStatus.FAILED);
        assertThat(notFound.email()).isNull();
        assertThat(failed.email()).isNull();
    }

    @Test
    void withoutLinkBeforeDeadlineIsProcessing() {
        assertThat(submitted().reportedStatus(null, DEADLINE.minusSeconds(1)))
                .isEqualTo(PartnerLinkRequestStatus.PROCESSING);
    }

    @Test
    void withoutLinkAtDeadlineIsExpired() {
        PartnerLinkRequest notFound = submitted();
        notFound.markNotFound();

        assertThat(submitted().reportedStatus(null, DEADLINE)).isEqualTo(PartnerLinkRequestStatus.EXPIRED);
        assertThat(notFound.reportedStatus(null, DEADLINE)).isEqualTo(PartnerLinkRequestStatus.EXPIRED);
    }

    @Test
    void pendingLinkBeforeItsDeadlineIsProcessing() {
        assertThat(submitted().reportedStatus(link(PartnerLinkStatus.PENDING), DEADLINE.minusSeconds(1)))
                .isEqualTo(PartnerLinkRequestStatus.PROCESSING);
    }

    @Test
    void pendingLinkAtItsDeadlineIsExpired() {
        assertThat(submitted().reportedStatus(link(PartnerLinkStatus.PENDING), DEADLINE))
                .isEqualTo(PartnerLinkRequestStatus.EXPIRED);
    }

    @ParameterizedTest
    @CsvSource({"CONNECTED,CONNECTED", "REJECTED,REJECTED", "EXPIRED,EXPIRED", "REVOKED,REVOKED"})
    void decidedLinkStatusIsPassedThrough(PartnerLinkStatus linkStatus, PartnerLinkRequestStatus reported) {
        PartnerLink link = link(linkStatus);

        assertThat(submitted().reportedStatus(link, DEADLINE.minusSeconds(1))).isEqualTo(reported);
        assertThat(submitted().reportedStatus(link, DEADLINE.plusSeconds(1))).isEqualTo(reported);
    }

    private static PartnerLinkRequest submitted() {
        return PartnerLinkRequest.submit(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "key-1",
                "external-1",
                "licensee-1",
                "user@example.test",
                UUID.randomUUID(),
                SUBMITTED,
                WINDOW);
    }

    private static PartnerLink link(PartnerLinkStatus status) {
        return PartnerLink.restore(
                UUID.randomUUID(),
                "user-1",
                "keycloak-1",
                UUID.randomUUID(),
                "external-1",
                "licensee-1",
                status,
                DEADLINE,
                0L,
                SUBMITTED,
                SUBMITTED);
    }
}
