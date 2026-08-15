package personal.albiondiscordbot.discord;

import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import personal.albiondiscordbot.domain.DiscordGuildConfig;

/**
 * Mirrors staff actions to the configured log channel.
 *
 * <p>Silent no-op when no log channel is set. Failures here are logged and swallowed —
 * an unreachable log channel must never make the command that triggered it fail, since
 * the authoritative record is the database ledger, not Discord.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public void record(CommandContext context, String title, String description, Color color) {
        record(context, context.config(), title, description, color);
    }

    /**
     * Logs against a config other than the one the command started with.
     *
     * <p>{@code /setup} needs this: it can set the log channel in the same invocation that
     * it is reporting, and the context still holds the configuration as it was before.
     */
    public void record(
            CommandContext context,
            DiscordGuildConfig config,
            String title,
            String description,
            Color color) {
        if (config == null || config.getLogChannelId() == null) {
            return;
        }
        TextChannel channel = context.guild().getTextChannelById(config.getLogChannelId());
        if (channel == null) {
            return;
        }
        try {
            channel.sendMessageEmbeds(new EmbedBuilder()
                            .setTitle(title)
                            .setDescription(description)
                            .setColor(color)
                            .setFooter("by " + context.member().getEffectiveName(),
                                    context.member().getEffectiveAvatarUrl())
                            .setTimestamp(java.time.Instant.now())
                            .build())
                    .queue(null, error -> log.warn("Could not write to the audit log channel", error));
        } catch (RuntimeException e) {
            log.warn("Could not write to the audit log channel", e);
        }
    }

    public void money(CommandContext context, String description) {
        record(context, "Balance change", description, new Color(0xF1C40F));
    }

    public void moderation(CommandContext context, String description) {
        record(context, "Registration change", description, new Color(0x9B59B6));
    }

    /**
     * Changes to who holds power or which in-game guild counts as ours.
     *
     * <p>These decide where silver ends up just as directly as a balance change does —
     * the staff role, the tracked guild, and the killboard channel are all inputs to who
     * gets paid — so they belong in the same place officers already look.
     */
    public void configuration(CommandContext context, DiscordGuildConfig config, String description) {
        record(context, config, "Configuration change", description, new Color(0x3498DB));
    }
}
