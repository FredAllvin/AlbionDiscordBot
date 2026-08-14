package personal.albiondiscordbot.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Discord bot configuration. The token is supplied by the {@code BOT_TOKEN}
 * environment variable; validation here means a missing token fails startup with a
 * readable message instead of a bare 401 from Discord.
 */
@Validated
@ConfigurationProperties(prefix = "bot")
public record BotProperties(@NotBlank String token) {
}
