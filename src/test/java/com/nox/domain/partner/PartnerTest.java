package com.nox.domain.partner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PartnerTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");

    @Test
    void activePartnerIsCallableWithinItsValidity() {
        Partner partner = partner(PartnerStatus.ACTIVE, TO);

        assertThat(partner.isCallableAt(FROM)).isTrue();
        assertThat(partner.isCallableAt(TO.minusSeconds(1))).isTrue();
    }

    @Test
    void activePartnerIsNotCallableOutsideItsValidity() {
        Partner partner = partner(PartnerStatus.ACTIVE, TO);

        assertThat(partner.isCallableAt(FROM.minusSeconds(1))).isFalse();
        assertThat(partner.isCallableAt(TO)).isFalse();
    }

    @Test
    void openEndedValidityHasNoUpperBound() {
        assertThat(partner(PartnerStatus.ACTIVE, null).isCallableAt(Instant.parse("2100-01-01T00:00:00Z")))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PartnerStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void inactivePartnerIsNeverCallable(PartnerStatus status) {
        assertThat(partner(status, null).isCallableAt(FROM.plusSeconds(60))).isFalse();
    }

    private static Partner partner(PartnerStatus status, Instant validTo) {
        return new Partner(UUID.randomUUID(), "partner-client", "Partner", null, status, FROM, validTo);
    }
}
