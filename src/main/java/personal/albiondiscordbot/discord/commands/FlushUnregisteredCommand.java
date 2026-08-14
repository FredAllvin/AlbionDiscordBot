package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
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
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.service.RegistrationService;

/**
 * {@code /flush-unregistered} — audits everyone holding the verified role.
 *
 * <p>Strips the role from anyone who is not registered, or whose character has left the
 * tracked guild. <strong>Dry run by default</strong>: an audit that removes roles from
 * dozens of people without showing the list first is far too easy to regret.
 */
@Component
public class FlushUnregisteredCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(FlushUnregisteredCommand.class);

    private final RegistrationService registrationService;
    private final TrackedAlbionGuildRepository trackedGuilds;

    public FlushUnregisteredCommand(
            RegistrationService registrationService, TrackedAlbionGuildRepository trackedGuilds) {
        this.registrationService = registrationService;
        this.trackedGuilds = trackedGuilds;
    }

    @Override
    public String name() {
        return "flush-unregistered";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash(
                        "flush-unregistered",
                        "Remove the verified role from anyone not registered or no longer in the guild")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(
                        new OptionData(
                                OptionType.BOOLEAN,
                                "confirm",
                                "Actually remove the roles. Without this it only reports what would change.",
                                false),
                        new OptionData(
                                OptionType.BOOLEAN,
                                "recheck_guild",
                                "Re-check every registration against the Albion API now (slower, default true)",
                                false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        Long verifiedRoleId = context.config().getVerifiedRoleId();
        if (verifiedRoleId == null) {
            throw new CommandException("No verified role is configured. Set one with `/setup`.");
        }
        Role role = context.guild().getRoleById(verifiedRoleId);
        if (role == null) {
            throw new CommandException("The configured verified role no longer exists.");
        }
        if (!context.guild().getSelfMember().canInteract(role)) {
            throw new CommandException(
                    "My highest role is below %s, so I cannot remove it. Move my role above it in Server Settings → Roles."
                            .formatted(role.getAsMention()));
        }

        boolean confirm = event.getOption("confirm", false, OptionMapping::getAsBoolean);
        // Defaults to true: catching people who left is the entire point of this command,
        // and defaulting it off made the audit quietly miss them.
        boolean recheckGuild = event.getOption("recheck_guild", true, OptionMapping::getAsBoolean);

        List<TrackedAlbionGuild> tracked = trackedGuilds.findByDiscordGuildId(context.guildId());
        List<Member> holders = context.guild().getMembersWithRoles(role);

        List<String> notRegistered = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        List<String> leftGuild = new ArrayList<>();
        List<Member> toStrip = new ArrayList<>();

        for (Member holder : holders) {
            if (holder.getUser().isBot()) {
                continue;
            }
            Optional<Registration> registration =
                    registrationService.find(context.guildId(), holder.getIdLong());

            if (registration.isEmpty()) {
                notRegistered.add(holder.getEffectiveName());
                toStrip.add(holder);
                continue;
            }
            if (recheckGuild && !tracked.isEmpty()) {
                try {
                    RegistrationService.MembershipCheck check =
                            registrationService.checkMembership(registration.get(), tracked);

                    if (check == RegistrationService.MembershipCheck.LEFT_GUILD) {
                        leftGuild.add("%s (%s)"
                                .formatted(holder.getEffectiveName(), registration.get().getAlbionPlayerName()));
                        toStrip.add(holder);
                        registrationService.recordValidation(registration.get(), false);
                    } else if (check == RegistrationService.MembershipCheck.IN_GUILD) {
                        registrationService.recordValidation(registration.get(), true);
                    } else {
                        // UNKNOWN: the API could not answer. Never strip a role on that basis.
                        unchecked.add(holder.getEffectiveName());
                    }
                } catch (RuntimeException e) {
                    // An API hiccup must never cost someone their role.
                    log.warn("Guild re-check failed for {}", registration.get().getAlbionPlayerName(), e);
                }
            }
        }

        if (confirm) {
            for (Member member : toStrip) {
                try {
                    context.guild()
                            .removeRoleFromMember(member, role)
                            .reason("flush-unregistered by " + context.member().getEffectiveName())
                            .queue();
                } catch (RuntimeException e) {
                    log.warn("Failed to remove verified role from {}", member.getId(), e);
                }
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(confirm ? "Flush complete" : "Flush preview (dry run)")
                .setColor(confirm ? new Color(0xE74C3C) : new Color(0xF1C40F))
                .addField("Role holders checked", Integer.toString(holders.size()), true)
                .addField("Would lose the role", Integer.toString(toStrip.size()), true);

        if (!notRegistered.isEmpty()) {
            embed.addField("Not registered (%d)".formatted(notRegistered.size()), truncate(notRegistered), false);
        }
        if (!leftGuild.isEmpty()) {
            embed.addField("No longer in the guild (%d)".formatted(leftGuild.size()), truncate(leftGuild), false);
        }
        if (toStrip.isEmpty()) {
            embed.setDescription("Everyone holding %s is properly registered.".formatted(role.getAsMention()));
        } else if (!confirm) {
            embed.setDescription("Nothing has changed yet. Re-run with `confirm: true` to apply.");
        }
        if (!unchecked.isEmpty()) {
            embed.addField(
                    "Could not check (%d)".formatted(unchecked.size()),
                    truncate(unchecked)
                            + "\nThe Albion API did not answer for these, so they were left alone "
                            + "rather than assumed to have left.",
                    false);
        }
        if (!recheckGuild) {
            embed.setFooter("Guild membership was NOT re-checked — only missing registrations were caught.");
        }

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private String truncate(List<String> names) {
        String joined = String.join(", ", names);
        return joined.length() <= 1000 ? joined : joined.substring(0, 1000) + "…";
    }
}
