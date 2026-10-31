package com.nox.adapter.out.messaging.partnerlink;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for {@link PartnerLinkStuckPublicationSweeper}. Shares the {@code nox.partner-link}
 * prefix with the application's partner link settings so the keys stay flat; each record binds only
 * its own fields.
 *
 * @param publicationSweepInterval how often failed publications are resubmitted and stuck ones flagged
 * @param stuckPublicationAge age after which an incomplete event publication is flagged
 */
@Validated
@ConfigurationProperties("nox.partner-link")
public record PartnerLinkPublicationSweepProperties(
        @NotNull @DefaultValue("1m") Duration publicationSweepInterval,
        @NotNull @DefaultValue("15m") Duration stuckPublicationAge) {}
