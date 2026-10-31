package com.nox.domain.partner;

import java.time.Instant;
import java.util.UUID;

/**
 * One changed field of a {@link Partner}, recorded append-only by whoever maintains partners.
 *
 * @param id identity
 * @param partnerId the changed partner
 * @param field the changed field, e.g. {@code status}
 * @param oldValue previous value; {@code null} when there was none
 * @param newValue new value
 * @param changedBy operator or system that made the change
 * @param occurredAt when the change was made
 * @param reason optional explanation
 */
public record PartnerHistoryEntry(
        UUID id,
        UUID partnerId,
        String field,
        String oldValue,
        String newValue,
        String changedBy,
        Instant occurredAt,
        String reason) {}
