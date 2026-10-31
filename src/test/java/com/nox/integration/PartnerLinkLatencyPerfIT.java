package com.nox.integration;

import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.APPROVED;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.EVENTS_EXCHANGE;
import static com.nox.adapter.out.messaging.partnerlink.PartnerLinkMessages.WEBHOOKS_EXCHANGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.OwlWireMockSupport;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-001: a link request is acknowledged within 2 s. SC-006: approval messages reach the broker
 * within 5 s of the commit at p95. Excluded from the default build; run with
 * {@code ./mvnw verify -Dgroups=perf}.
 */
@Tag("perf")
class PartnerLinkLatencyPerfIT extends PartnerLinkIntegrationSupport {

    private static final int WARM_UP = 20;
    private static final int SAMPLES = 100;
    private static final int APPROVALS = 20;
    private static final Duration MAX_ACKNOWLEDGMENT = Duration.ofSeconds(2);
    private static final Duration MAX_P95_DELIVERY = Duration.ofSeconds(5);

    @Test
    void linkRequestsAreAcknowledgedWithinTwoSeconds() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.noUser(email);
        for (int i = 0; i < WARM_UP; i++) {
            api.submit(partner, email);
        }

        List<Duration> samples = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            api.submit(partner, email);
            samples.add(Duration.ofNanos(System.nanoTime() - started));
        }

        assertThat(Collections.max(samples)).isLessThan(MAX_ACKNOWLEDGMENT);
    }

    @Test
    void approvalMessagesAreDeliveredWithinFiveSecondsAtP95() {
        String userMessages = queues.bind(EVENTS_EXCHANGE, APPROVED);
        String partnerMessages = queues.bind(WEBHOOKS_EXCHANGE, APPROVED);
        String alice = TOKENS.user("alice");
        List<Link> pending = new ArrayList<>(APPROVALS);
        for (int i = 0; i < APPROVALS; i++) {
            pending.add(scenarios.pendingLink(partner));
        }

        List<Duration> samples = new ArrayList<>(APPROVALS);
        for (Link link : pending) {
            api.postAsInternalBff(link.userId(), link.path() + "/approve");
            long committed = System.nanoTime();
            queues.awaitFirst(userMessages, mentioning(link.linkId()), Duration.ofSeconds(30));
            queues.awaitFirst(partnerMessages, mentioning(link.linkId()), Duration.ofSeconds(30));
            samples.add(Duration.ofNanos(System.nanoTime() - committed));
        }

        Collections.sort(samples);
        assertThat(samples.get((int) Math.ceil(APPROVALS * 0.95) - 1)).isLessThan(MAX_P95_DELIVERY);
    }
}
