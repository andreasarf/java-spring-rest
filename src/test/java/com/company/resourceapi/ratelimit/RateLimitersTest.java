package com.company.resourceapi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.resourceapi.config.RateLimitProperties.Limit;
import com.company.resourceapi.services.RateLimiters;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

/** Behavior without a working Redis; with one, see RateLimitIntegrationTest. */
class RateLimitersTest {

    private static final Limit LIMIT = new Limit(1, Duration.ofSeconds(1), Duration.ZERO);

    @Test
    void givenRedisFailsThenRequestsAreAllowedWithoutAskingItAgainForAWhile() {
        var redisson = mock(RedissonClient.class);
        when(redisson.getRateLimiter(anyString())).thenThrow(new IllegalStateException("Redis is down"));
        var rateLimiters = new RateLimiters(providerOf(redisson));

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiters.tryAcquire("test", LIMIT)).isTrue();
        }

        // one failed attempt, then Redis is left alone instead of each request waiting for its timeout
        verify(redisson, times(1)).getRateLimiter(anyString());
    }

    @Test
    void givenNoRedissonClientThenRequestsAreAllowed() {
        var rateLimiters = new RateLimiters(providerOf(null));

        assertThat(rateLimiters.tryAcquire("test", LIMIT)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedissonClient> providerOf(RedissonClient redisson) {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisson);
        return provider;
    }
}
