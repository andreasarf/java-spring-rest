package com.company.resourceapi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.config.RateLimitProperties.Limit;
import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitPropertiesTest {

    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void givenInvalidLimitsThenTheyAreRejected() {
        var noPermits = new Limit(0, Duration.ofSeconds(1), Duration.ZERO);
        var noPeriod = new Limit(5, null, Duration.ZERO);
        var valid = new Limit(5, Duration.ofSeconds(1), Duration.ZERO);

        assertThat(validator.validate(new RateLimitProperties(noPermits, valid, valid))).hasSize(1);
        assertThat(validator.validate(new RateLimitProperties(valid, noPeriod, valid))).hasSize(1);
        assertThat(validator.validate(new RateLimitProperties(valid, valid, valid))).isEmpty();
    }

    @Test
    void givenLimitThenRetryAfterIsOneFullWindow() {
        assertThat(new Limit(6, Duration.ofMinutes(1), Duration.ZERO).retryAfterSeconds()).isEqualTo(60);
        assertThat(new Limit(10, Duration.ofMillis(500), Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
    }
}
