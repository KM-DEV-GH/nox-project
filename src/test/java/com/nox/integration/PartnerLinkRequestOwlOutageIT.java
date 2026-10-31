package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.LookupStatus;
import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.domain.partnerlink.PartnerLinkStatus;
import com.nox.integration.support.KeycloakContainerSupport;
import com.nox.integration.support.OwlWireMockSupport;
import com.nox.integration.support.PartnerFixtures;
import com.nox.integration.support.PartnerLinkApi;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * FR-002: an Owl outage never changes the answer to the partner. The test context allows 3 attempts
 * with a 1 s read timeout (InfrastructureContainers).
 */
class PartnerLinkRequestOwlOutageIT extends KeycloakContainerSupport {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final int MAX_ATTEMPTS = 3;

    private final OwlWireMockSupport owl = OwlWireMockSupport.forContainer();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PartnerLinkRequestRepository requests;

    @Autowired
    private PartnerLinkRepository links;

    private PartnerLinkApi api;
    private Partner partner;

    @BeforeEach
    void setUp() {
        api = new PartnerLinkApi(client, TOKENS);
        partner = new PartnerFixtures(jdbc).active();
    }

    @Test
    void aTimeoutIsRetriedAndTheLinkCreatedAfterRecovery() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.timesOutThenFinds(email, Duration.ofSeconds(3), UUID.randomUUID().toString());

        long started = System.nanoTime();
        EntityExchangeResult<String> accepted =
                api.submit(partner, UUID.randomUUID().toString(), "external-1", email);
        Duration answeredIn = Duration.ofNanos(System.nanoTime() - started);

        assertThat(accepted.getStatus().value()).isEqualTo(202);
        assertThat(answeredIn).as("the answer never waits for Owl").isLessThan(Duration.ofSeconds(2));
        PartnerLinkRequest request = awaitLookup(PartnerLinkApi.requestIdOf(accepted));
        assertThat(request.lookupStatus()).isEqualTo(LookupStatus.RESOLVED);
        assertThat(links.findById(request.linkId()).orElseThrow().status()).isEqualTo(PartnerLinkStatus.PENDING);
        assertThat(owl.lookupsFor(email)).isEqualTo(2);
    }

    @Test
    void anOutageLongerThanTheRetriesFailsTheLookupWithoutALink() {
        String email = OwlWireMockSupport.uniqueEmail();
        owl.status(email, 503);

        EntityExchangeResult<String> accepted =
                api.submit(partner, UUID.randomUUID().toString(), "external-2", email);

        assertThat(accepted.getStatus().value()).isEqualTo(202);
        PartnerLinkRequest request = awaitLookup(PartnerLinkApi.requestIdOf(accepted));
        assertThat(request.lookupStatus()).isEqualTo(LookupStatus.FAILED);
        assertThat(request.email()).isNull();
        assertThat(request.linkId()).isNull();
        assertThat(owl.lookupsFor(email)).isEqualTo(MAX_ATTEMPTS);
    }

    private PartnerLinkRequest awaitLookup(UUID requestId) {
        await().atMost(ASYNC)
                .until(() -> requests.findById(requestId)
                        .map(request -> request.lookupStatus() != LookupStatus.PENDING)
                        .orElse(false));
        return requests.findById(requestId).orElseThrow();
    }
}
