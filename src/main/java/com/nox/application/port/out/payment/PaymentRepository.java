package com.nox.application.port.out.payment;

import com.nox.domain.payment.Payment;
import java.util.Optional;
import java.util.UUID;

/** Stores payments. Each call is its own short transaction; none spans a payment-core call. */
public interface PaymentRepository {

    /**
     * Stores a new payment, reserving its partner-scoped idempotency key and external ID.
     *
     * @return the stored payment, with its version
     * @throws DuplicatePaymentException when the partner already used the key
     * @throws DuplicateExternalIdException when the partner already has a payment with the external ID
     */
    Payment insert(Payment payment);

    /**
     * Stores the change to a stored payment.
     *
     * @return the stored payment, with its new version
     * @throws ConcurrentPaymentUpdateException when another transaction changed it since it was read
     */
    Payment save(Payment payment);

    Optional<Payment> findByPartnerAndIdempotencyKey(UUID partnerId, String idempotencyKey);

    Optional<Payment> findByPaymentCoreId(String paymentCoreId);

    /** {@code true} when any stored payment of the partner, whatever its status, has {@code externalId}. */
    boolean existsByPartnerAndExternalId(UUID partnerId, String externalId);

    /** Releases a reservation that never reached payment-core. */
    void delete(UUID id);
}
