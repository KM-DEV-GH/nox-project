package com.nox.adapter.out.persistence.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByPartnerIdAndIdempotencyKey(UUID partnerId, String idempotencyKey);

    Optional<PaymentEntity> findByPaymentCoreId(String paymentCoreId);

    boolean existsByPartnerIdAndExternalId(UUID partnerId, String externalId);
}
