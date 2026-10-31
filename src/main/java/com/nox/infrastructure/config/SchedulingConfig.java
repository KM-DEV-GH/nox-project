package com.nox.infrastructure.config;

import com.nox.adapter.out.messaging.partnerlink.PartnerLinkPublicationSweepProperties;
import com.nox.adapter.out.messaging.partnerlink.PartnerLinkStuckPublicationSweeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

/** Background jobs, registered here so their intervals come from configuration, not annotations. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

    @Bean
    SchedulingConfigurer partnerLinkPublicationSweep(
            PartnerLinkStuckPublicationSweeper sweeper, PartnerLinkPublicationSweepProperties properties) {
        return registrar -> registrar.addFixedDelayTask(sweeper::sweep, properties.publicationSweepInterval());
    }
}
