package com.nox.application.service.payment;

import com.nox.application.port.in.payment.InitiatePaymentUseCase;
import com.nox.application.port.in.payment.PaymentException;
import com.nox.application.port.in.payment.PaymentFailure;
import com.nox.application.port.out.partnerlink.PartnerLinkRepository;
import com.nox.application.port.out.payment.DuplicateExternalIdException;
import com.nox.application.port.out.payment.DuplicatePaymentException;
import com.nox.application.port.out.payment.PartnerPaymentSettingsRepository;
import com.nox.application.port.out.payment.PaymentProcessorPort;
import com.nox.application.port.out.payment.PaymentProcessorPort.InitiationRequest;
import com.nox.application.port.out.payment.PaymentProcessorPort.ProcessorResult;
import com.nox.application.port.out.payment.PaymentRepository;
import com.nox.domain.partnerlink.PartnerLink;
import com.nox.domain.payment.PartnerPaymentAccount;
import com.nox.domain.payment.Payment;
import com.nox.domain.payment.PaymentStatus;
import com.nox.domain.payment.PaymentType;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Initiates a payment (research.md D2, D5). The partner-scoped key is reserved in its own short
 * transaction before payment-core is called, with the payment's id as payment-core's idempotency
 * key, so neither a retry nor a concurrent duplicate can create a second upstream payment. The
 * partner's external ID names at most one payment (FR-022). No database transaction is held during
 * the payment-core call.
 */
public class InitiatePaymentService implements InitiatePaymentUseCase {

    private final PaymentLinks links;
    private final PaymentRepository payments;
    private final PartnerPaymentSettingsRepository settings;
    private final PaymentProcessorPort processor;
    private final Clock clock;
    private final Map<PaymentType, String> methods;

    public InitiatePaymentService(
            PartnerLinkRepository links,
            PaymentRepository payments,
            PartnerPaymentSettingsRepository settings,
            PaymentProcessorPort processor,
            Clock clock,
            Map<PaymentType, String> methods) {
        this.links = new PaymentLinks(links);
        this.payments = payments;
        this.settings = settings;
        this.processor = processor;
        this.clock = clock;
        this.methods = methods.isEmpty() ? Map.of() : new EnumMap<>(methods);
    }

    @Override
    public Initiation initiate(Command command) {
        PartnerLink link = links.resolve(command.partnerId(), command.link());
        String fingerprint = PaymentFingerprint.of(link.id(), command);
        var existing = payments.findByPartnerAndIdempotencyKey(command.partnerId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        PaymentLinks.requireConnected(link);
        if (payments.existsByPartnerAndExternalId(command.partnerId(), command.externalId())) {
            throw new PaymentException(PaymentFailure.EXTERNAL_ID_CONFLICT);
        }
        Payment reserved;
        try {
            reserved = payments.insert(newPayment(command, link, fingerprint));
        } catch (DuplicatePaymentException e) {
            return replay(
                    payments.findByPartnerAndIdempotencyKey(command.partnerId(), command.idempotencyKey())
                            .orElseThrow(() -> new PaymentException(PaymentFailure.IN_PROGRESS)),
                    fingerprint);
        } catch (DuplicateExternalIdException e) {
            throw new PaymentException(PaymentFailure.EXTERNAL_ID_CONFLICT);
        }
        return new Initiation(process(reserved, link), true);
    }

    private Payment newPayment(Command command, PartnerLink link, String fingerprint) {
        boolean allowed = settings.findLimit(
                        command.partnerId(), command.type(), command.money().currencyCode())
                .filter(limit -> limit.allows(command.money()))
                .isPresent();
        if (!allowed) {
            throw new PaymentException(PaymentFailure.LIMIT_EXCEEDED);
        }
        String method = methods.get(command.type());
        PartnerPaymentAccount account = settings.findAccount(command.partnerId())
                .orElseThrow(() -> new PaymentException(PaymentFailure.NOT_CONFIGURED));
        if (method == null) {
            throw new PaymentException(PaymentFailure.NOT_CONFIGURED);
        }
        return Payment.initiate(
                UUID.randomUUID(),
                command.partnerId(),
                link.id(),
                account,
                command.idempotencyKey(),
                fingerprint,
                command.type(),
                method,
                command.money(),
                command.externalId(),
                command.transactionReference(),
                command.description(),
                clock.instant());
    }

    private Payment process(Payment payment, PartnerLink link) {
        ProcessorResult result = processor.initiate(new InitiationRequest(
                payment.id(),
                payment.type(),
                payment.paymentMethod(),
                payment.providerUserIdSnapshot(),
                payment.accountIdSnapshot(),
                link.userId(),
                payment.money(),
                payment.transactionReference()));
        switch (result) {
            case ProcessorResult.Completed completed -> {
                payment.recordInitiation(completed.payment(), clock.instant());
                Payment stored = payments.save(payment);
                if (stored.status() == PaymentStatus.INITIATION_UNKNOWN) {
                    throw new PaymentException(PaymentFailure.UNRESOLVED);
                }
                return stored;
            }
            case ProcessorResult.Rejected rejected -> {
                payment.rejectInitiation(clock.instant());
                payments.save(payment);
                throw new PaymentException(PaymentFailure.REJECTED);
            }
            case ProcessorResult.NotAttempted notAttempted -> {
                payments.delete(payment.id());
                throw new PaymentException(PaymentFailure.PROCESSOR_UNAVAILABLE);
            }
            case ProcessorResult.Unknown unknown -> {
                payment.initiationUnknown(clock.instant());
                payments.save(payment);
                throw new PaymentException(PaymentFailure.UNRESOLVED);
            }
        }
    }

    /** The outcome recorded for the key, whatever the link, account or limits are now (FR-008). */
    private static Initiation replay(Payment existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new PaymentException(PaymentFailure.IDEMPOTENCY_CONFLICT);
        }
        if (existing.status() == PaymentStatus.INITIATING) {
            throw new PaymentException(PaymentFailure.IN_PROGRESS);
        }
        if (existing.status() == PaymentStatus.INITIATION_UNKNOWN) {
            throw new PaymentException(PaymentFailure.UNRESOLVED);
        }
        if (existing.paymentCoreId() == null) {
            throw new PaymentException(PaymentFailure.REJECTED);
        }
        return new Initiation(existing, false);
    }
}
