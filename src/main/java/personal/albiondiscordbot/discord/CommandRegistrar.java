package personal.albiondiscordbot.discord;

import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes the command definitions to each Discord server as it becomes ready.
 *
 * <p>Registration is <strong>guild-scoped</strong> rather than global: guild-scoped
 * updates take effect immediately, while global commands can take up to an hour to
 * propagate. This bot is per-server anyway.
 *
 * <p>All commands are registered regardless of whether {@code /setup} has run —
 * otherwise {@code /setup} itself could never appear. Commands that need
 * configuration check for it at execution time.
 */
@Component
public class CommandRegistrar extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrar.class);

    private final CommandRegistry registry;

    public CommandRegistrar(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        register(event.getGuild());
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        register(event.getGuild());
    }

    public void register(Guild guild) {
        List<SlashCommandData> definitions =
                registry.all().stream().map(SlashCommand::definition).toList();

        guild.updateCommands()
                .addCommands(definitions)
                .queue(
                        ok -> log.info(
                                "Registered {} commands in guild '{}' ({})",
                                definitions.size(),
                                guild.getName(),
                                guild.getId()),
                        error -> log.error(
                                "Failed to register commands in guild '{}' ({})",
                                guild.getName(),
                                guild.getId(),
                                error));
    }
}
