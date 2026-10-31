package com.nox.integration.support;

import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.partner.Partner;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentType;
import com.nox.integration.support.PaymentFixtures.EstablishedLink;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Base class for partner payment integration tests: a fresh partner per test with an active account
 * and EUR limits of 1–1000 for both types, one connected link, the payment API as the partner BFF
 * calls it, and the payment-core stubs.
 */
public abstract class PartnerPaymentIntegrationSupport extends KeycloakContainerSupport {

    protected static final String PROVIDER_USER_ID = "provider-user-1";
    protected static final String ACCOUNT_ID = "account-1";

    protected final PaymentCoreWireMockSupport paymentCore = PaymentCoreWireMockSupport.forContainer();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PaymentRepository payments;

    protected PaymentApi api;
    protected PaymentFixtures fixtures;
    protected PartnerFixtures partners;
    protected Partner partner;
    protected EstablishedLink link;

    @BeforeEach
    void setUpPayments() {
        api = new PaymentApi(client, TOKENS);
        partners = new PartnerFixtures(jdbc);
        fixtures = new PaymentFixtures(jdbc);
        partner = partners.active();
        fixtures.activeAccount(partner, PROVIDER_USER_ID, ACCOUNT_ID);
        fixtures.standardLimits(partner);
        link = fixtures.connectedLink(partner);
    }

    /** Initiates a payment payment-core accepts and returns it, awaiting the OTP. */
    protected Payment awaitingOtp(PaymentType type, String payments, String amount) {
        String reference = PaymentCoreWireMockSupport.uniqueReference();
        String paymentCoreId = PaymentCoreWireMockSupport.uniquePaymentCoreId();
        paymentCore.initiationAccepted(type, reference, paymentCoreId, amount, "EUR");
        EntityExchangeResult<String> created =
                api.initiate(partner, payments, UUID.randomUUID().toString(), PaymentApi.body(type.name(), amount, "EUR", reference));
        if (created.getStatus().value() != 201) {
            throw new IllegalStateException("Initiation failed: " + created.getResponseBody());
        }
        return this.payments.findByPaymentCoreId(paymentCoreId).orElseThrow();
    }

    protected int paymentRows(Partner owner) {
        return jdbc.sql("select count(*) from payment where partner_id = :partnerId")
                .param("partnerId", owner.id())
                .query(Integer.class)
                .single();
    }
}
