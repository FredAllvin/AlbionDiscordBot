package personal.albiondiscordbot.discord;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
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
 *
 * <p>Autocomplete arrives here too, and gets neither of those: it cannot be deferred at
 * all, and it runs on a pool of its own rather than queueing behind commands that are
 * allowed to take twenty seconds.
 */
@Component
public class SlashCommandListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandListener.class);

    private final CommandRegistry registry;
    private final GuildConfigService guildConfigService;
    private final PermissionService permissionService;
    private final Executor executor;
    private final Executor autoCompleteExecutor;

    public SlashCommandListener(
            CommandRegistry registry,
            GuildConfigService guildConfigService,
            PermissionService permissionService,
            @Qualifier("commandExecutor") Executor executor,
            @Qualifier("autoCompleteExecutor") Executor autoCompleteExecutor) {
        this.registry = registry;
        this.guildConfigService = guildConfigService;
        this.permissionService = permissionService;
        this.executor = executor;
        this.autoCompleteExecutor = autoCompleteExecutor;
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

        // Decided before deferring, because the deferral is what fixes it: the reply that
        // follows fills the placeholder this puts in the channel and inherits its privacy.
        boolean inPublic = !command.ephemeral(event);

        event.deferReply(!inPublic).queue();
        try {
            executor.execute(() -> run(command, event, inPublic));
        } catch (RejectedExecutionException e) {
            // The pool is full. Saying so beats the alternative of running the command on
            // this gateway thread, which would stall every server the bot is in.
            log.warn("Command /{} rejected — the command pool is saturated", command.name());
            reply(event, inPublic, "The bot is busy right now — give it a moment and try that again.");
        }
    }

    /**
     * Suggestions for an autocompleting option, from the command that will run it.
     *
     * <p>Off the gateway thread for the reason {@link #onSlashCommandInteraction} is, and
     * on a pool of its own because this one has three seconds and no way to ask for more.
     *
     * <p>Every failure ends the same way — an empty list. There is no error channel here:
     * an autocomplete has no message to reply into, so the worst outcome that can be
     * reported is no suggestions, and the caller can still type the value by hand.
     */
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        SlashCommand command = registry.find(event.getName()).orElse(null);
        if (command == null || event.getGuild() == null) {
            event.replyChoices(List.of()).queue();
            return;
        }
        try {
            autoCompleteExecutor.execute(() -> suggest(command, event));
        } catch (RejectedExecutionException e) {
            log.warn("Autocomplete for /{} rejected — the suggestion pool is saturated", command.name());
            event.replyChoices(List.of()).queue();
        }
    }

    private void suggest(SlashCommand command, CommandAutoCompleteInteractionEvent event) {
        List<Command.Choice> choices;
        try {
            choices = command.autocomplete(event);
        } catch (Exception e) {
            log.warn("Autocomplete for /{} failed in guild {}", command.name(), event.getGuild().getId(), e);
            choices = List.of();
        }
        if (choices.size() > OptionData.MAX_CHOICES) {
            // replyChoices would throw, and an exception on this thread leaves the caller
            // watching a box that never fills in. Cut it and say so instead.
            log.warn("Autocomplete for /{} returned {} choices; showing the first {}",
                    command.name(), choices.size(), OptionData.MAX_CHOICES);
            choices = choices.subList(0, OptionData.MAX_CHOICES);
        }
        // The interaction may have expired while this was queued, which is not worth an
        // error line — it means the caller has typed another character since.
        event.replyChoices(choices).queue(null, error -> log.debug("Autocomplete reply dropped", error));
    }

    private void run(SlashCommand command, SlashCommandInteractionEvent event, boolean inPublic) {
        try {
            DiscordGuildConfig config =
                    guildConfigService.find(event.getGuild().getIdLong()).orElse(null);

            if (command.requiresSetup() && (config == null || !config.isSetupCompleted())) {
                reply(
                        event,
                        inPublic,
                        "This server is not set up yet. An administrator needs to run `/setup` first.");
                return;
            }
            if (command.staffOnly()) {
                permissionService.requireStaff(event.getMember(), config);
            }

            command.execute(event, new CommandContext(event.getGuild(), event.getMember(), config));

        } catch (CommandException e) {
            reply(event, inPublic, e.getMessage());
        } catch (Exception e) {
            log.error("Command /{} failed in guild {}", command.name(), event.getGuild().getId(), e);
            reply(
                    event,
                    inPublic,
                    "Something went wrong running that command. The error has been logged.");
        }
    }

    /**
     * Failures always come back to the caller alone, however public the command was.
     *
     * <p>A public command that failed has already put a placeholder in the channel, and
     * the reply that fills it would be public too. "You are not registered" is not guild
     * news, so the placeholder is taken back down first and the message follows as an
     * ephemeral follow-up. The delete is allowed to fail — an expired or already-removed
     * placeholder is no reason to swallow the explanation.
     */
    private void reply(SlashCommandInteractionEvent event, boolean inPublic, String message) {
        if (!inPublic) {
            event.getHook().sendMessage(message).setEphemeral(true).queue();
            return;
        }
        event.getHook()
                .deleteOriginal()
                .mapToResult()
                .flatMap(ignored -> event.getHook().sendMessage(message).setEphemeral(true))
                .queue();
    }
}
