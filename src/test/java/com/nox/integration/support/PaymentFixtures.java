package com.nox.integration.support;

import com.nox.domain.partner.Partner;
import com.nox.domain.payment.PaymentType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts the internally managed payment settings (account, limits) and established links for
 * payment tests. Neither has a partner API, and link creation is covered by the 002 tests, so rows
 * are written directly.
 */
public class PaymentFixtures {

    private final JdbcClient jdbc;

    public PaymentFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes {@code accountId} the partner's active payment-core account, replacing any previous one. */
    public void activeAccount(Partner partner, String providerUserId, String accountId) {
        jdbc.sql("""
                insert into partner_payment_account (partner_id, provider_user_id, account_id, updated_at)
                values (:partnerId, :providerUserId, :accountId, now())
                on conflict (partner_id) do update
                    set provider_user_id = excluded.provider_user_id, account_id = excluded.account_id,
                        updated_at = excluded.updated_at
                """)
                .param("partnerId", partner.id())
                .param("providerUserId", providerUserId)
                .param("accountId", accountId)
                .update();
    }

    public void limit(Partner partner, PaymentType type, String currencyCode, boolean enabled, String min, String max) {
        jdbc.sql("""
                insert into partner_payment_limit (partner_id, type, currency_code, enabled, minimum_amount, maximum_amount)
                values (:partnerId, :type, :currency, :enabled, :min, :max)
                """)
                .param("partnerId", partner.id())
                .param("type", type.name())
                .param("currency", currencyCode)
                .param("enabled", enabled)
                .param("min", new BigDecimal(min))
                .param("max", new BigDecimal(max))
                .update();
    }

    /** An enabled EUR limit of 1–1000 for both types. */
    public void standardLimits(Partner partner) {
        limit(partner, PaymentType.PAYIN, "EUR", true, "1", "1000");
        limit(partner, PaymentType.PAYOUT, "EUR", true, "1", "1000");
    }

    /** A link of {@code status} for a new user, with the partner's reference {@code externalId}. */
    public EstablishedLink link(Partner partner, String status, String externalId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        String userId = UUID.randomUUID().toString();
        Timestamp at = Timestamp.from(createdAt);
        jdbc.sql("""
                insert into partner_link (id, user_id, partner_id, partner_external_id, licensee_id,
                    status, expires_at, version, created_at, updated_at)
                values (:id, :userId, :partnerId, :externalId, 'licensee-1', :status, :at, 0, :at, :at)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("partnerId", partner.id())
                .param("externalId", externalId)
                .param("status", status)
                .param("at", at)
                .update();
        return new EstablishedLink(id, userId, externalId);
    }

    public EstablishedLink connectedLink(Partner partner) {
        return link(partner, "CONNECTED", "external-" + UUID.randomUUID(), Instant.now());
    }

    /**
     * @param linkId NOX {@code userLinkId}
     * @param userId Owl {@code user_id}, sent to payment-core as {@code client.userId}
     * @param externalId the partner's {@code externalLinkId}
     */
    public record EstablishedLink(UUID linkId, String userId, String externalId) {}
}
