package com.company.resourceapi.notifications;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Project notifications for other systems.
 *
 * @param exchange RabbitMQ topic exchange the notifications are sent to
 * @param resubmitInterval how often unsent notifications are looked for
 * @param resubmitAfter how old an unsent notification must be before it is sent again; longer than a
 *     normal send takes, so one still on its way isn't sent twice
 */
@Validated
@ConfigurationProperties("resourceapi.notifications")
public record NotificationsProperties(
        @DefaultValue("resourceapi.projects") @NotBlank String exchange,
        @DefaultValue("1m") @NotNull Duration resubmitInterval,
        @DefaultValue("1m") @NotNull Duration resubmitAfter) {
}
