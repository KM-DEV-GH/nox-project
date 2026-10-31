package com.nox.infrastructure.config;

import com.nox.adapter.in.web.partnerlink.ActingUserIdArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Handler parameters resolved by the web adapter, e.g. the user the internal BFF acts for. */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ActingUserIdArgumentResolver());
    }
}
