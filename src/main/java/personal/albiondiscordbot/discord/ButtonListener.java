package personal.albiondiscordbot.discord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.service.GuildConfigService;

/**
 * Routes button clicks to the right {@link ButtonHandler}.
 *
 * <p>Mirrors {@link SlashCommandListener}: the interaction is acknowledged immediately
 * and the work runs on a worker thread, because Discord closes the response window after
 * 3 seconds and blocking JDA's gateway thread would stall the whole bot.
 *
 * <p>Permissions are re-checked <em>at click time</em>, not trusted from whenever the
 * button was created. A button that has been sitting in a channel since before someone
 * lost their staff role must not still work for them.
 */
@Component
public class ButtonListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ButtonListener.class);

    private final Map<String, ButtonHandler> handlers;
    private final GuildConfigService guildConfigService;
    private final PermissionService permissionService;
    private final Executor executor;

    public ButtonListener(
            List<ButtonHandler> handlers,
            GuildConfigService guildConfigService,
            PermissionService permissionService,
            @Qualifier("commandExecutor") Executor executor) {
        Map<String, ButtonHandler> map = new LinkedHashMap<>();
        for (ButtonHandler handler : handlers) {
            ButtonHandler existing = map.put(handler.prefix(), handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate button prefix '" + handler.prefix() + "'");
            }
        }
        this.handlers = Map.copyOf(map);
        this.guildConfigService = guildConfigService;
        this.permissionService = permissionService;
        this.executor = executor;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        String[] parts = customId.split(":");
        if (parts.length < 2) {
            return;
        }
        ButtonHandler handler = handlers.get(parts[0]);
        if (handler == null || event.getGuild() == null || event.getMember() == null) {
            return;
        }

        event.deferEdit().queue();
        String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        try {
            executor.execute(() -> run(handler, event, args));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Never fall back to running this on the gateway thread — see AsyncConfig.
            log.warn("Button {} rejected — the command pool is saturated", customId);
            event.getHook()
                    .sendMessage("The bot is busy right now — give it a moment and click that again.")
                    .setEphemeral(true)
                    .queue();
        }
    }

    private void run(ButtonHandler handler, ButtonInteractionEvent event, String[] args) {
        try {
            DiscordGuildConfig config =
                    guildConfigService.find(event.getGuild().getIdLong()).orElse(null);

            if (handler.staffOnly()) {
                permissionService.requireStaff(event.getMember(), config);
            }
            handler.handle(event, args, new CommandContext(event.getGuild(), event.getMember(), config));

        } catch (CommandException e) {
            event.getHook().sendMessage(e.getMessage()).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("Button {} failed in guild {}", event.getComponentId(), event.getGuild().getId(), e);
            event.getHook()
                    .sendMessage("Something went wrong. The error has been logged.")
                    .setEphemeral(true)
                    .queue();
        }
    }
}
