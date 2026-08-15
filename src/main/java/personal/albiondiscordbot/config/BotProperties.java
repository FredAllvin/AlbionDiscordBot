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

    /**
     * Masked. A record's generated {@code toString} prints every component, so anything
     * that logs this object — a binding failure, a debug dump, a future call site that
     * looks harmless — would put a live bot token in the logs.
     */
    @Override
    public String toString() {
        return "BotProperties[token=****]";
    }
}
