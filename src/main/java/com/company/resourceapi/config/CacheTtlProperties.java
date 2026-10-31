package com.company.resourceapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Time to live of the Redis caches ({@code resourceapi.cache.*}).
 *
 * @param sdlcSystemsTtl      SDLC systems by id; they change rarely and only outside this API
 * @param externalProjectsTtl projects as described by their SDLC system ({@code GET .../external})
 */
@ConfigurationProperties("resourceapi.cache")
public record CacheTtlProperties(
        @DefaultValue("10m") Duration sdlcSystemsTtl,
        @DefaultValue("5m") Duration externalProjectsTtl) {
}
