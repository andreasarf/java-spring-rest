package com.company.resourceapi.web;

import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.services.RateLimiters;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Registers {@link ClientRateLimitFilter} for the API only (not Swagger UI or the OpenAPI document). */
@Configuration
public class ClientRateLimitConfig {

    @Bean
    FilterRegistrationBean<ClientRateLimitFilter> clientRateLimitFilter(RateLimiters rateLimiters,
            RateLimitProperties rateLimits,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        var registration = new FilterRegistrationBean<>(
                new ClientRateLimitFilter(rateLimiters, rateLimits, exceptionResolver));
        registration.addUrlPatterns("/api/*");
        // after the server observation (+1) and trace id (+2) filters
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
