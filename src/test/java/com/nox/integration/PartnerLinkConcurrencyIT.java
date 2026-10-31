package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLinkHistoryEntry;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.domain.partnerlink.TransitionReason;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Spec edge case "concurrent transitions": optimistic locking leaves one consistent final state and
 * exactly one history entry per written transition, whoever wins the race.
 */
class PartnerLinkConcurrencyIT extends PartnerLinkIntegrationSupport {

    private static final Duration PAST_DEADLINE = Duration.ofMinutes(2).plusSeconds(1);

    @Autowired
    private IncompleteEventPublications incompletePublications;

    @RepeatedTest(3)
    void aLateApprovalRacingASupersedingRequestExpiresTheLinkOnce() {
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner);
        clock.advance(PAST_DEADLINE);

        List<Object> results = race(
                () -> api.postAsInternalBff(link.userId(), link.path() + "/approve"),
                () -> api.submit(partner, UUID.randomUUID().toString(), "external-" + UUID.randomUUID(), link.email()));

        EntityExchangeResult<?> approval = (EntityExchangeResult<?>) results.get(0);
        assertThat(approval.getStatus().value()).isEqualTo(409);
        UUID supersedingId = PartnerLinkApi.requestIdOf(castToString(results.get(1)));
        awaitResolution(supersedingId);

        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.EXPIRED);
        assertThat(history.findByLinkId(link.linkId()))
                .filteredOn(entry -> entry.toStatus() == PartnerLinkStatus.EXPIRED)
                .hasSize(1)
                .extracting(PartnerLinkHistoryEntry::reason)
                .containsAnyOf(TransitionReason.EXPIRED_ON_LATE_ACTION, TransitionReason.EXPIRED_ON_NEW_REQUEST);
        PartnerLinkRequest resolved = requests.findById(supersedingId).orElseThrow();
        assertThat(resolved.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(resolved.linkId()).isNotEqualTo(link.linkId());
        assertThat(links.findById(resolved.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.PENDING);
    }

    @RepeatedTest(3)
    void twoConcurrentApprovalsConnectOnce() {
        String alice = TOKENS.user("alice");
        Link link = scenarios.pendingLink(partner);

        List<Object> results = race(
                () -> api.postAsInternalBff(link.userId(), link.path() + "/approve"),
                () -> api.postAsInternalBff(link.userId(), link.path() + "/approve"));

        assertThat(results)
                .extracting(
                        result -> ((EntityExchangeResult<?>) result).getStatus().value())
                .containsExactlyInAnyOrder(200, 409);
        assertThat(links.findById(link.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.CONNECTED);
        assertThat(history.findByLinkId(link.linkId()))
                .extracting(PartnerLinkHistoryEntry::reason)
                .containsExactly(TransitionReason.LINK_CREATED, TransitionReason.USER_APPROVED);
    }

    /** Starts both calls at the same moment and waits for both. */
    private static List<Object> race(Supplier<?> first, Supplier<?> second) {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Object> one = CompletableFuture.supplyAsync(() -> awaitThen(start, first));
        CompletableFuture<Object> two = CompletableFuture.supplyAsync(() -> awaitThen(start, second));
        start.countDown();
        return List.of(one.join(), two.join());
    }

    private static Object awaitThen(CountDownLatch start, Supplier<?> call) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return call.get();
    }

    /**
     * A resolution that lost the race is rolled back and its publication marked failed; resubmitting
     * failed publications (the publication sweeper's job in production) completes it.
     */
    private void awaitResolution(UUID requestId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    incompletePublications.resubmitIncompletePublications(ResubmissionOptions.defaults());
                    return requests.findById(requestId).orElseThrow().lookupStatus() != LookupStatus.PENDING;
                });
    }

    @SuppressWarnings("unchecked")
    private static EntityExchangeResult<String> castToString(Object result) {
        return (EntityExchangeResult<String>) result;
    }
}
