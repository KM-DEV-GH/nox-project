package com.nox.application.fake;

import com.nox.application.port.out.payment.ConcurrentPaymentUpdateException;
import com.nox.application.port.out.payment.DuplicateExternalIdException;
import com.nox.application.port.out.payment.DuplicatePaymentException;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.payment.Payment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores copies, enforces the partner-scoped key and external ID, and checks versions like the JPA adapter. */
public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<UUID, Payment> payments = new LinkedHashMap<>();
    private Runnable beforeNextWrite;

    /** Runs {@code write} at the start of the next insert or save, as if another request had committed it first. */
    public void interleave(Runnable write) {
        beforeNextWrite = write;
    }

    public int size() {
        return payments.size();
    }

    public Payment get(UUID id) {
        return copy(payments.get(id), payments.get(id).version());
    }

    public Payment only() {
        return payments.values().stream()
                .reduce((first, second) -> {
                    throw new AssertionError("more than one payment stored");
                })
                .map(payment -> copy(payment, payment.version()))
                .orElseThrow();
    }

    @Override
    public Payment insert(Payment payment) {
        runInterleaved();
        if (findByPartnerAndIdempotencyKey(payment.partnerId(), payment.idempotencyKey())
                .isPresent()) {
            throw new DuplicatePaymentException();
        }
        if (existsByPartnerAndExternalId(payment.partnerId(), payment.externalId())) {
            throw new DuplicateExternalIdException();
        }
        Payment stored = copy(payment, 0L);
        payments.put(payment.id(), stored);
        return copy(stored, 0L);
    }

    @Override
    public Payment save(Payment payment) {
        runInterleaved();
        Payment stored = payments.get(payment.id());
        if (stored == null || !Objects.equals(stored.version(), payment.version())) {
            throw new ConcurrentPaymentUpdateException(payment.id());
        }
        Payment saved = copy(payment, stored.version() + 1);
        payments.put(payment.id(), saved);
        return copy(saved, saved.version());
    }

    @Override
    public Optional<Payment> findByPartnerAndIdempotencyKey(UUID partnerId, String idempotencyKey) {
        return payments.values().stream()
                .filter(payment -> payment.partnerId().equals(partnerId)
                        && payment.idempotencyKey().equals(idempotencyKey))
                .findFirst()
                .map(payment -> copy(payment, payment.version()));
    }

    @Override
    public boolean existsByPartnerAndExternalId(UUID partnerId, String externalId) {
        return payments.values().stream()
                .anyMatch(payment -> payment.partnerId().equals(partnerId)
                        && payment.externalId().equals(externalId));
    }

    @Override
    public Optional<Payment> findByPaymentCoreId(String paymentCoreId) {
        return payments.values().stream()
                .filter(payment -> paymentCoreId.equals(payment.paymentCoreId()))
                .findFirst()
                .map(payment -> copy(payment, payment.version()));
    }

    @Override
    public void delete(UUID id) {
        payments.remove(id);
    }

    private void runInterleaved() {
        if (beforeNextWrite != null) {
            Runnable write = beforeNextWrite;
            beforeNextWrite = null;
            write.run();
        }
    }

    private static Payment copy(Payment payment, Long version) {
        return Payment.restore(
                payment.id(),
                payment.paymentCoreId(),
                payment.partnerId(),
                payment.userLinkId(),
                payment.providerUserIdSnapshot(),
                payment.accountIdSnapshot(),
                payment.idempotencyKey(),
                payment.requestFingerprint(),
                payment.type(),
                payment.paymentMethod(),
                payment.money(),
                payment.externalId(),
                payment.transactionReference(),
                payment.description(),
                payment.fee(),
                payment.status(),
                payment.createdAt(),
                payment.paymentCoreCreatedAt(),
                payment.expirationDate(),
                payment.confirmedDate(),
                payment.updatedAt(),
                version);
    }
}
