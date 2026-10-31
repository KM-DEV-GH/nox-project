package com.nox.domain.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PartnerLinkTest {

    private static final Instant CREATED = Instant.parse("2026-10-26T09:00:00Z");
    private static final Instant DEADLINE = CREATED.plus(Duration.ofHours(72));
    private static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(1);

    @Test
    void createsPendingLinkWithoutVersion() {
        PartnerLink link = pending();

        assertThat(link.status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(link.expiresAt()).isEqualTo(DEADLINE);
        assertThat(link.createdAt()).isEqualTo(CREATED);
        assertThat(link.version()).isNull();
    }

    @Test
    void approveBeforeDeadlineConnects() {
        PartnerLink link = pending();

        assertThat(link.approve(BEFORE_DEADLINE)).isEqualTo(LinkDecisionOutcome.APPROVED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.CONNECTED);
        assertThat(link.updatedAt()).isEqualTo(BEFORE_DEADLINE);
    }

    @Test
    void approveAtDeadlineExpires() {
        PartnerLink link = pending();

        assertThat(link.approve(DEADLINE)).isEqualTo(LinkDecisionOutcome.EXPIRED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.EXPIRED);
    }

    @Test
    void approveAfterDeadlineExpires() {
        PartnerLink link = pending();

        assertThat(link.approve(DEADLINE.plusSeconds(60))).isEqualTo(LinkDecisionOutcome.EXPIRED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.EXPIRED);
    }

    @Test
    void rejectBeforeDeadlineRejects() {
        PartnerLink link = pending();

        assertThat(link.reject(BEFORE_DEADLINE)).isEqualTo(LinkDecisionOutcome.REJECTED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.REJECTED);
    }

    @Test
    void rejectAtDeadlineExpires() {
        PartnerLink link = pending();

        assertThat(link.reject(DEADLINE)).isEqualTo(LinkDecisionOutcome.EXPIRED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.EXPIRED);
    }

    @Test
    void revokeConnectedLink() {
        PartnerLink link = withStatus(PartnerLinkStatus.CONNECTED);

        assertThat(link.revoke(DEADLINE.plusSeconds(1))).isEqualTo(LinkDecisionOutcome.REVOKED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.REVOKED);
    }

    @ParameterizedTest
    @EnumSource(value = PartnerLinkStatus.class, names = "CONNECTED", mode = EnumSource.Mode.EXCLUDE)
    void revokeIsNotActionableUnlessConnected(PartnerLinkStatus status) {
        PartnerLink link = withStatus(status);

        assertThat(link.revoke(BEFORE_DEADLINE)).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(link.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = PartnerLinkStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void decisionsOnDecidedLinkAreNotActionable(PartnerLinkStatus status) {
        PartnerLink approved = withStatus(status);
        PartnerLink rejected = withStatus(status);

        assertThat(approved.approve(BEFORE_DEADLINE)).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(rejected.reject(DEADLINE.plusSeconds(1))).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(approved.status()).isEqualTo(status);
        assertThat(rejected.status()).isEqualTo(status);
    }

    @Test
    void effectiveStatusDerivesExpiryWithoutWriting() {
        PartnerLink link = pending();

        assertThat(link.effectiveStatus(BEFORE_DEADLINE)).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(link.effectiveStatus(DEADLINE)).isEqualTo(PartnerLinkStatus.EXPIRED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(link.updatedAt()).isEqualTo(CREATED);
    }

    @Test
    void effectiveStatusOfDecidedLinkIsItsStatus() {
        assertThat(withStatus(PartnerLinkStatus.CONNECTED).effectiveStatus(DEADLINE.plusSeconds(1)))
                .isEqualTo(PartnerLinkStatus.CONNECTED);
    }

    @Test
    void supersedingOverduePendingLinkExpiresIt() {
        PartnerLink link = pending();

        assertThat(link.supersede(DEADLINE)).isEqualTo(LinkDecisionOutcome.EXPIRED);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.EXPIRED);
    }

    @Test
    void supersedingLinkBeforeItsDeadlineIsNotActionable() {
        PartnerLink link = pending();

        assertThat(link.supersede(BEFORE_DEADLINE)).isEqualTo(LinkDecisionOutcome.NOT_ACTIONABLE);
        assertThat(link.status()).isEqualTo(PartnerLinkStatus.PENDING);
    }

    @Test
    void onlyConnectedAndRevokedLinksAreEstablished() {
        assertThat(withStatus(PartnerLinkStatus.CONNECTED).isEstablished()).isTrue();
        assertThat(withStatus(PartnerLinkStatus.REVOKED).isEstablished()).isTrue();
        assertThat(withStatus(PartnerLinkStatus.PENDING).isEstablished()).isFalse();
        assertThat(withStatus(PartnerLinkStatus.REJECTED).isEstablished()).isFalse();
        assertThat(withStatus(PartnerLinkStatus.EXPIRED).isEstablished()).isFalse();
    }

    private static PartnerLink pending() {
        return PartnerLink.pending(
                UUID.randomUUID(), "user-1", UUID.randomUUID(), "external-1", "licensee-1", DEADLINE, CREATED);
    }

    private static PartnerLink withStatus(PartnerLinkStatus status) {
        return PartnerLink.restore(
                UUID.randomUUID(),
                "user-1",
                UUID.randomUUID(),
                "external-1",
                "licensee-1",
                status,
                DEADLINE,
                3L,
                CREATED,
                CREATED);
    }
}
