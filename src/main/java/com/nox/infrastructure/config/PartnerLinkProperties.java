package com.nox.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level settings for partner link management. Read only by {@link ConfiguredBeansConfig},
 * which passes plain values to the services (research.md decision 8). Adapter settings under the
 * same prefix are bound by the adapters' own records.
 *
 * @param approvalWindow time a user has to decide, counted from submission (spec FR-017)
 */
@Validated
@ConfigurationProperties("nox.partner-link")
public record PartnerLinkProperties(@NotNull Duration approvalWindow) {}
