package com.nox.adapter.out.persistence.payment;

import com.nox.application.port.out.payment.ConcurrentPaymentUpdateException;
import com.nox.application.port.out.payment.DuplicateExternalIdException;
import com.nox.application.port.out.payment.DuplicatePaymentException;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.payment.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/** Flushes every write at once, so a lost update, a reused key or a reused external ID surfaces here. */
@Repository
public class JpaPaymentRepository implements PaymentRepository {

    private final PaymentJpaRepository payments;

    public JpaPaymentRepository(PaymentJpaRepository payments) {
        this.payments = payments;
    }

    @Override
    public Payment insert(Payment payment) {
        try {
            return payments.saveAndFlush(PaymentEntity.of(payment)).toDomain();
        } catch (DataIntegrityViolationException e) {
            if (findByPartnerAndIdempotencyKey(payment.partnerId(), payment.idempotencyKey())
                    .isPresent()) {
                throw new DuplicatePaymentException();
            }
            if (existsByPartnerAndExternalId(payment.partnerId(), payment.externalId())) {
                throw new DuplicateExternalIdException();
            }
            throw e;
        }
    }

    @Override
    public Payment save(Payment payment) {
        try {
            return payments.saveAndFlush(PaymentEntity.of(payment)).toDomain();
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentPaymentUpdateException(payment.id());
        }
    }

    @Override
    public Optional<Payment> findByPartnerAndIdempotencyKey(UUID partnerId, String idempotencyKey) {
        return payments.findByPartnerIdAndIdempotencyKey(partnerId, idempotencyKey)
                .map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByPaymentCoreId(String paymentCoreId) {
        return payments.findByPaymentCoreId(paymentCoreId).map(PaymentEntity::toDomain);
    }

    @Override
    public boolean existsByPartnerAndExternalId(UUID partnerId, String externalId) {
        return payments.existsByPartnerIdAndExternalId(partnerId, externalId);
    }

    @Override
    public void delete(UUID id) {
        payments.deleteById(id);
    }
}
