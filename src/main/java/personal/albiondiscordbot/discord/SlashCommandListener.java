package personal.albiondiscordbot.discord;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.service.GuildConfigService;

/**
 * Receives interactions and hands them to the right {@link SlashCommand}.
 *
 * <p>Two things happen here that are load-bearing rather than incidental:
 *
 * <ol>
 *   <li>The interaction is <strong>deferred immediately</strong>. Discord closes the
 *       initial-response window after 3 seconds, and commands that call the Albion API
 *       or iterate hundreds of members routinely take longer.
 *   <li>The command body runs on a <strong>worker thread, not JDA's gateway
 *       thread</strong>. Blocking the gateway thread stalls the entire bot — every
 *       other server's commands included — so this handoff is mandatory.
 * </ol>
 */
@Component
public class SlashCommandListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandListener.class);

    private final CommandRegistry registry;
    private final GuildConfigService guildConfigService;
    private final PermissionService permissionService;
    private final Executor executor;

    public SlashCommandListener(
            CommandRegistry registry,
            GuildConfigService guildConfigService,
            PermissionService permissionService,
            @Qualifier("commandExecutor") Executor executor) {
        this.registry = registry;
        this.guildConfigService = guildConfigService;
        this.permissionService = permissionService;
        this.executor = executor;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        SlashCommand command = registry.find(event.getName()).orElse(null);
        if (command == null) {
            event.reply("Unknown command `%s`.".formatted(event.getName())).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild() == null || event.getMember() == null) {
            event.reply("This command only works inside a server.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(command.ephemeral()).queue();
        try {
            executor.execute(() -> run(command, event));
        } catch (RejectedExecutionException e) {
            // The pool is full. Saying so beats the alternative of running the command on
            // this gateway thread, which would stall every server the bot is in.
            log.warn("Command /{} rejected — the command pool is saturated", command.name());
            reply(event, "The bot is busy right now — give it a moment and try that again.");
        }
    }

    private void run(SlashCommand command, SlashCommandInteractionEvent event) {
        try {
            DiscordGuildConfig config =
                    guildConfigService.find(event.getGuild().getIdLong()).orElse(null);

            if (command.requiresSetup() && (config == null || !config.isSetupCompleted())) {
                reply(event, "This server is not set up yet. An administrator needs to run `/setup` first.");
                return;
            }
            if (command.staffOnly()) {
                permissionService.requireStaff(event.getMember(), config);
            }

            command.execute(event, new CommandContext(event.getGuild(), event.getMember(), config));

        } catch (CommandException e) {
            reply(event, e.getMessage());
        } catch (Exception e) {
            log.error("Command /{} failed in guild {}", command.name(), event.getGuild().getId(), e);
            reply(event, "Something went wrong running that command. The error has been logged.");
        }
    }

    private void reply(SlashCommandInteractionEvent event, String message) {
        event.getHook().sendMessage(message).setEphemeral(true).queue();
    }
}
