package com.nox.application.service.payment;

import com.nox.application.port.in.payment.InitiatePaymentUseCase.Command;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SHA-256 of the normalized initiation request, so a reused idempotency key with different details
 * is detected (spec FR-009). The amount is compared by value; every field is length-prefixed so
 * no two different requests share an encoding.
 */
final class PaymentFingerprint {

    private PaymentFingerprint() {}

    static String of(UUID userLinkId, Command command) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, userLinkId.toString());
        append(canonical, command.type().name());
        append(canonical, command.money().amount().stripTrailingZeros().toPlainString());
        append(canonical, command.money().currencyCode());
        append(canonical, command.externalId());
        append(canonical, command.transactionReference());
        append(canonical, command.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    private static void append(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-1:");
            return;
        }
        canonical.append(value.length()).append(':').append(value);
    }
}
