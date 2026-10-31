package com.nox.application.service.partnerlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.application.event.PartnerLinkRequestSubmitted;
import com.nox.application.fake.FixedTraceIdSource;
import com.nox.application.fake.InMemoryPartnerLinkRequestRepository;
import com.nox.application.fake.RecordingEventPublisher;
import com.nox.application.fake.StubUserLookup;
import com.nox.application.port.in.partnerlink.SubmitPartnerLinkRequestUseCase.Command;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmitPartnerLinkRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);
    private static final UUID PARTNER_ID = UUID.randomUUID();

    private final InMemoryPartnerLinkRequestRepository requests = new InMemoryPartnerLinkRequestRepository();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final StubUserLookup userLookup = new StubUserLookup();
    private final SubmitPartnerLinkRequestService service = new SubmitPartnerLinkRequestService(
            requests, events, new FixedTraceIdSource(), Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);

    @Test
    void storesThePendingRequestWithItsDeadline() {
        UUID requestId = service.submit(command("key-1"));

        PartnerLinkRequest stored = requests.get(requestId);
        assertThat(stored.partnerId()).isEqualTo(PARTNER_ID);
        assertThat(stored.idempotencyKey()).isEqualTo("key-1");
        assertThat(stored.partnerExternalId()).isEqualTo("external-1");
        assertThat(stored.licenseeId()).isEqualTo("licensee-1");
        assertThat(stored.email()).isEqualTo("user@example.test");
        assertThat(stored.lookupStatus()).isEqualTo(LookupStatus.PENDING);
        assertThat(stored.submittedAt()).isEqualTo(NOW);
        assertThat(stored.expiresAt()).isEqualTo(NOW.plus(WINDOW));
        assertThat(stored.correlationId()).isNotNull();
    }

    @Test
    void publishesTheSubmissionAndNeverLooksUpTheUser() {
        UUID requestId = service.submit(command("key-2"));

        assertThat(events.eventsOfType(PartnerLinkRequestSubmitted.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.requestId()).isEqualTo(requestId);
                    assertThat(event.traceId()).isEqualTo(FixedTraceIdSource.TRACE_ID);
                    assertThat(event.correlationId())
                            .isEqualTo(requests.get(requestId).correlationId());
                    assertThat(event.messageId()).isNotNull();
                });
        assertThat(userLookup.lookedUp()).isEmpty();
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheOriginalRequest() {
        UUID original = service.submit(command("key-3"));

        UUID repeated = service.submit(command("key-3"));

        assertThat(repeated).isEqualTo(original);
        assertThat(requests.size()).isEqualTo(1);
        assertThat(events.events()).hasSize(1);
    }

    @Test
    void theSameKeyFromAnotherPartnerIsANewRequest() {
        UUID first = service.submit(command("key-4"));

        UUID other = service.submit(
                new Command(UUID.randomUUID(), "key-4", "external-1", "user@example.test", "licensee-1"));

        assertThat(other).isNotEqualTo(first);
        assertThat(requests.size()).isEqualTo(2);
    }

    private static Command command(String idempotencyKey) {
        return new Command(PARTNER_ID, idempotencyKey, "external-1", "user@example.test", "licensee-1");
    }
}
