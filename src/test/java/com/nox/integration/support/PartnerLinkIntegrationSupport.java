package com.nox.integration.support;

import com.nox.application.port.out.partnerlink.PartnerLinkHistoryRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.partnerlink.PartnerLinkRequestRepository;
import com.nox.domain.partner.Partner;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base class for partner link integration tests: a fresh partner per test, the API as the BFF and
 * users call it, the Owl stubs, test queues on NOX's exchanges, and the persistence ports for
 * checking what was stored.
 */
public abstract class PartnerLinkIntegrationSupport extends KeycloakContainerSupport {

    protected final OwlWireMockSupport owl = OwlWireMockSupport.forContainer();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PartnerLinkRequestRepository requests;

    @Autowired
    protected PartnerLinkRepository links;

    @Autowired
    protected PartnerLinkHistoryRepository history;

    protected PartnerLinkApi api;
    protected TestQueues queues;
    protected LinkScenarios scenarios;
    protected PartnerFixtures partners;
    protected Partner partner;

    @BeforeEach
    void setUpPartnerLinks(@Autowired AmqpAdmin admin, @Autowired RabbitTemplate rabbit) {
        api = new PartnerLinkApi(client, TOKENS);
        queues = new TestQueues(admin, rabbit);
        scenarios = new LinkScenarios(api, owl, requests);
        partners = new PartnerFixtures(jdbc);
        partner = partners.active();
    }

    /** Messages whose body mentions {@code id}, e.g. a link id. */
    protected static Predicate<Message> mentioning(UUID id) {
        return message -> new String(message.getBody(), StandardCharsets.UTF_8).contains(id.toString());
    }
}
