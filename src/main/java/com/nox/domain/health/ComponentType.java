package com.nox.domain.health;

import java.util.Locale;

/** Kind of dependency a health check covers. */
public enum ComponentType {
    REDIS,
    POSTGRESQL,
    MONGODB,
    RABBITMQ,
    EXTERNAL_SERVICE;

    /** The contract value: lowercase, words separated by hyphens (e.g. {@code external-service}). */
    public String code() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
