package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.AuditLogService;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.service.RegistrationService;
import personal.albiondiscordbot.util.Formatting;

/** {@code /register <ingame name>} — self-service, verified against the live API. */
@Component
public class RegisterCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(RegisterCommand.class);

    /**
     * Minimum gap between one member's registration attempts.
     *
     * <p>Every Albion call goes through a single global rate limiter, so an unthrottled
     * member holding it repeatedly queues ahead of the battle poller and delays killboard
     * posts. Registering is a once-per-character action, so a few seconds costs nobody
     * anything.
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final RegistrationService registrationService;
    private final AuditLogService auditLog;

    /**
     * Last attempt per (server, member). Bounded by the membership of the servers the bot
     * is in, and entries are tiny, so this is left to grow rather than swept — a scheduled
     * cleaner would be more moving parts than the thing it manages.
     */
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    public RegisterCommand(RegistrationService registrationService, AuditLogService auditLog) {
        this.registrationService = registrationService;
        this.auditLog = auditLog;
    }

    @Override
    public String name() {
        return "register";
    }

    /**
     * Public: linking a Discord account to a character is the one thing everyone else in
     * the guild has a reason to see, because it is what makes a name in the killboard and
     * a name in the channel the same person.
     */
    @Override
    public boolean ephemeral(SlashCommandInteractionEvent event) {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("register", "Link your Albion character to your Discord account")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(new OptionData(
                                OptionType.STRING, "name", "Your exact in-game character name", true)
                        .setMaxLength(64));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        String characterName = event.getOption("name", OptionMapping::getAsString).trim();
        enforceCooldown(context);

        Registration registration =
                registrationService.register(context.guildId(), context.callerId(), characterName);

        // The bot cannot prove this member owns this character — only that the character
        // is in a tracked guild. Since /split-cta pays by registration, a claim on someone
        // else's name redirects their share, and the only remedy is an officer noticing.
        // So every claim is announced where officers already watch for balance changes.
        auditLog.moderation(context, "%s registered as **%s** (`%s`)".formatted(
                context.member().getAsMention(),
                Formatting.escapeMarkdown(registration.getAlbionPlayerName()),
                registration.getAlbionPlayerId()));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Registered")
                .setColor(new Color(0x2ECC71))
                .setDescription("You are now registered as **%s**."
                        .formatted(Formatting.escapeMarkdown(registration.getAlbionPlayerName())));

        grantVerifiedRole(context, embed);

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    /**
     * Recorded before the lookup rather than after it, so a burst of failing attempts is
     * throttled too — those are the ones that spend API calls without producing anything.
     */
    private void enforceCooldown(CommandContext context) {
        String key = context.guildId() + ":" + context.callerId();
        Instant now = Instant.now();

        Instant previous = lastAttempt.put(key, now);
        if (previous != null && previous.isAfter(now.minus(COOLDOWN))) {
            // Put the earlier stamp back: otherwise hammering the command keeps pushing
            // the window forward and the member can never get through.
            lastAttempt.put(key, previous);
            long wait = Duration.between(now, previous.plus(COOLDOWN)).toSeconds() + 1;
            throw new CommandException(
                    "You just tried that. Give it %d second%s and try again."
                            .formatted(wait, wait == 1 ? "" : "s"));
        }
    }

    private void grantVerifiedRole(CommandContext context, EmbedBuilder embed) {
        Long verifiedRoleId = context.config().getVerifiedRoleId();
        if (verifiedRoleId == null) {
            return;
        }
        Role role = context.guild().getRoleById(verifiedRoleId);
        if (role == null) {
            embed.addField("Note", "The configured verified role no longer exists.", false);
            return;
        }
        if (!context.guild().getSelfMember().hasPermission(Permission.MANAGE_ROLES)
                || !context.guild().getSelfMember().canInteract(role)) {
            embed.addField(
                    "Note",
                    "I could not give you %s — my role needs to be above it and I need Manage Roles."
                            .formatted(role.getAsMention()),
                    false);
            return;
        }
        try {
            context.guild().addRoleToMember(context.member(), role).reason("Registered in-game name").queue();
            embed.addField("Role granted", role.getAsMention(), false);
        } catch (RuntimeException e) {
            log.warn("Failed to grant verified role in guild {}", context.guildId(), e);
            embed.addField("Note", "Registered, but I could not assign the role.", false);
        }
    }
}
