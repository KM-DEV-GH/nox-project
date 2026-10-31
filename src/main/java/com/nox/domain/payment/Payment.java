package com.nox.domain.payment;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One pay-in or pay-out and its rules (data-model.md: Payment). Payment-core is the authority for
 * the outcome: NOX records what it reports and never infers success. Confirmation and cancellation
 * both start from {@code AWAITING_OTP} and move the payment out of it before calling payment-core,
 * so with optimistic locking at most one of them can start (spec FR-019).
 */
public final class Payment {

    private static final Set<PaymentStatus> INITIATION_RESULTS =
            EnumSet.of(PaymentStatus.AWAITING_OTP, PaymentStatus.FAILED, PaymentStatus.EXPIRED);
    private static final Set<PaymentStatus> ACTION_RESULTS = EnumSet.of(
            PaymentStatus.AWAITING_OTP,
            PaymentStatus.CONFIRMED,
            PaymentStatus.CANCELLED,
            PaymentStatus.FAILED,
            PaymentStatus.EXPIRED);

    private final UUID id;
    private final UUID partnerId;
    private final UUID userLinkId;
    private final String providerUserIdSnapshot;
    private final String accountIdSnapshot;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final PaymentType type;
    private final String paymentMethod;
    private final Money money;
    private final String externalId;
    private final String transactionReference;
    private final String description;
    private final Instant createdAt;
    private final Long version;
    private String paymentCoreId;
    private Money fee;
    private PaymentStatus status;
    private Instant paymentCoreCreatedAt;
    private Instant expirationDate;
    private Instant confirmedDate;
    private Instant updatedAt;

    private Payment(
            UUID id,
            String paymentCoreId,
            UUID partnerId,
            UUID userLinkId,
            String providerUserIdSnapshot,
            String accountIdSnapshot,
            String idempotencyKey,
            String requestFingerprint,
            PaymentType type,
            String paymentMethod,
            Money money,
            String externalId,
            String transactionReference,
            String description,
            Money fee,
            PaymentStatus status,
            Instant createdAt,
            Instant paymentCoreCreatedAt,
            Instant expirationDate,
            Instant confirmedDate,
            Instant updatedAt,
            Long version) {
        this.id = id;
        this.paymentCoreId = paymentCoreId;
        this.partnerId = partnerId;
        this.userLinkId = userLinkId;
        this.providerUserIdSnapshot = providerUserIdSnapshot;
        this.accountIdSnapshot = accountIdSnapshot;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.type = type;
        this.paymentMethod = paymentMethod;
        this.money = money;
        this.externalId = externalId;
        this.transactionReference = transactionReference;
        this.description = description;
        this.fee = fee;
        this.status = status;
        this.createdAt = createdAt;
        this.paymentCoreCreatedAt = paymentCoreCreatedAt;
        this.expirationDate = expirationDate;
        this.confirmedDate = confirmedDate;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * A reservation made before payment-core is called: its {@code id} is the stable upstream
     * idempotency key, and {@code account} is copied so later account changes do not affect it.
     */
    public static Payment initiate(
            UUID id,
            UUID partnerId,
            UUID userLinkId,
            PartnerPaymentAccount account,
            String idempotencyKey,
            String requestFingerprint,
            PaymentType type,
            String paymentMethod,
            Money money,
            String externalId,
            String transactionReference,
            String description,
            Instant now) {
        if (!money.isPositive()) {
            throw new IllegalArgumentException("A payment needs a positive amount");
        }
        return new Payment(
                id,
                null,
                partnerId,
                userLinkId,
                account.providerUserId(),
                account.accountId(),
                idempotencyKey,
                requestFingerprint,
                type,
                paymentMethod,
                money,
                externalId,
                transactionReference,
                description,
                null,
                PaymentStatus.INITIATING,
                now,
                null,
                null,
                null,
                now,
                null);
    }

    /** A payment as stored; {@code version} is {@code null} only for one never stored. */
    public static Payment restore(
            UUID id,
            String paymentCoreId,
            UUID partnerId,
            UUID userLinkId,
            String providerUserIdSnapshot,
            String accountIdSnapshot,
            String idempotencyKey,
            String requestFingerprint,
            PaymentType type,
            String paymentMethod,
            Money money,
            String externalId,
            String transactionReference,
            String description,
            Money fee,
            PaymentStatus status,
            Instant createdAt,
            Instant paymentCoreCreatedAt,
            Instant expirationDate,
            Instant confirmedDate,
            Instant updatedAt,
            Long version) {
        return new Payment(
                id,
                paymentCoreId,
                partnerId,
                userLinkId,
                providerUserIdSnapshot,
                accountIdSnapshot,
                idempotencyKey,
                requestFingerprint,
                type,
                paymentMethod,
                money,
                externalId,
                transactionReference,
                description,
                fee,
                status,
                createdAt,
                paymentCoreCreatedAt,
                expirationDate,
                confirmedDate,
                updatedAt,
                version);
    }

    /**
     * Payment-core created the payment. An echo of different money, or a status NOX does not expect
     * here, leaves it {@code INITIATION_UNKNOWN} rather than reporting success.
     */
    public void recordInitiation(ProcessorPayment processed, Instant now) {
        require(PaymentStatus.INITIATING);
        paymentCoreId = processed.paymentCoreId();
        absorb(processed);
        moveTo(trusted(processed, INITIATION_RESULTS) ? processed.status() : PaymentStatus.INITIATION_UNKNOWN, now);
    }

    /** Payment-core refused to create the payment. */
    public void rejectInitiation(Instant now) {
        require(PaymentStatus.INITIATING);
        moveTo(PaymentStatus.FAILED, now);
    }

    /** No authoritative answer: payment-core may or may not have created the payment. */
    public void initiationUnknown(Instant now) {
        require(PaymentStatus.INITIATING);
        moveTo(PaymentStatus.INITIATION_UNKNOWN, now);
    }

    /** Claims the payment for confirmation; {@code false} if it is not awaiting the OTP. */
    public boolean beginConfirmation(Instant now) {
        return claim(PaymentStatus.CONFIRMING, now);
    }

    /** Claims the payment for cancellation; {@code false} if it is not awaiting the OTP. */
    public boolean beginCancellation(Instant now) {
        return claim(PaymentStatus.CANCELLING, now);
    }

    /** Payment-core's answer to the confirmation or cancellation in flight. */
    public void recordOutcome(ProcessorPayment processed, Instant now) {
        PaymentStatus action = requireAction();
        absorb(processed);
        if (processed.confirmedAt() != null) {
            confirmedDate = processed.confirmedAt();
        }
        moveTo(trusted(processed, ACTION_RESULTS) ? processed.status() : unknownAfter(action), now);
    }

    /** Payment-core refused the action, or was never called: the payment still awaits the OTP. */
    public void returnToAwaitingOtp(Instant now) {
        requireAction();
        moveTo(PaymentStatus.AWAITING_OTP, now);
    }

    /** No authoritative answer to the action in flight. */
    public void outcomeUnknown(Instant now) {
        moveTo(unknownAfter(requireAction()), now);
    }

    private boolean claim(PaymentStatus action, Instant now) {
        if (status != PaymentStatus.AWAITING_OTP) {
            return false;
        }
        moveTo(action, now);
        return true;
    }

    private boolean trusted(ProcessorPayment processed, Set<PaymentStatus> expected) {
        return processed.status() != null
                && expected.contains(processed.status())
                && processed.money() != null
                && processed.money().sameAs(money);
    }

    private void absorb(ProcessorPayment processed) {
        if (processed.fee() != null) {
            fee = processed.fee();
        }
        if (processed.createdAt() != null) {
            paymentCoreCreatedAt = processed.createdAt();
        }
        if (processed.expiresAt() != null) {
            expirationDate = processed.expiresAt();
        }
    }

    private PaymentStatus requireAction() {
        if (status != PaymentStatus.CONFIRMING && status != PaymentStatus.CANCELLING) {
            throw new IllegalStateException("No confirmation or cancellation is in flight for payment " + id);
        }
        return status;
    }

    private void require(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Payment " + id + " is " + status + ", not " + expected);
        }
    }

    private static PaymentStatus unknownAfter(PaymentStatus action) {
        return action == PaymentStatus.CONFIRMING
                ? PaymentStatus.CONFIRMATION_UNKNOWN
                : PaymentStatus.CANCELLATION_UNKNOWN;
    }

    private void moveTo(PaymentStatus next, Instant now) {
        status = next;
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    /** Payment-core's identifier, the partner-facing {@code paymentId}; {@code null} until payment-core answers. */
    public String paymentCoreId() {
        return paymentCoreId;
    }

    public UUID partnerId() {
        return partnerId;
    }

    public UUID userLinkId() {
        return userLinkId;
    }

    public String providerUserIdSnapshot() {
        return providerUserIdSnapshot;
    }

    public String accountIdSnapshot() {
        return accountIdSnapshot;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public PaymentType type() {
        return type;
    }

    public String paymentMethod() {
        return paymentMethod;
    }

    public Money money() {
        return money;
    }

    public String externalId() {
        return externalId;
    }

    public String transactionReference() {
        return transactionReference;
    }

    public String description() {
        return description;
    }

    public Money fee() {
        return fee;
    }

    public PaymentStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant paymentCoreCreatedAt() {
        return paymentCoreCreatedAt;
    }

    public Instant expirationDate() {
        return expirationDate;
    }

    public Instant confirmedDate() {
        return confirmedDate;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
