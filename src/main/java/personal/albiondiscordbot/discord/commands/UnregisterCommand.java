package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
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
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.AuditLogService;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.PermissionService;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.service.RegistrationService;
import personal.albiondiscordbot.util.Formatting;

/**
 * {@code /unregister [@user]} — removes a registration.
 *
 * <p>With no argument it unregisters the caller. Naming someone else requires staff,
 * and is the remedy for a wrong name or an impersonation attempt.
 */
@Component
public class UnregisterCommand implements SlashCommand {

    private final RegistrationService registrationService;
    private final PermissionService permissions;
    private final AuditLogService auditLog;

    public UnregisterCommand(
            RegistrationService registrationService, PermissionService permissions, AuditLogService auditLog) {
        this.registrationService = registrationService;
        this.permissions = permissions;
        this.auditLog = auditLog;
    }

    @Override
    public String name() {
        return "unregister";
    }

    /** Public, for the same reason {@code /register} is: it changes who is who. */
    @Override
    public boolean ephemeral(SlashCommandInteractionEvent event) {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("unregister", "Remove a registration (staff can unregister anyone)")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(new OptionData(
                        OptionType.USER, "user", "Whose registration to remove (staff only)", false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        Member target = event.getOption("user", OptionMapping::getAsMember);

        boolean targetingSomeoneElse = target != null && target.getIdLong() != context.callerId();
        if (targetingSomeoneElse) {
            permissions.requireStaff(context.member(), context.config());
        }
        Member subject = target != null ? target : context.member();

        Optional<Registration> removed = registrationService.unregister(
                context.guildId(), subject.getIdLong(), context.callerId());

        if (removed.isEmpty()) {
            throw new CommandException(
                    targetingSomeoneElse
                            ? "%s is not registered.".formatted(subject.getAsMention())
                            : "You are not registered.");
        }

        auditLog.moderation(context, "%s unregistered from **%s**".formatted(
                subject.getAsMention(), Formatting.escapeMarkdown(removed.get().getAlbionPlayerName())));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Unregistered")
                .setColor(new Color(0xE74C3C))
                .setDescription("%s is no longer registered as **%s**."
                        .formatted(
                                subject.getAsMention(),
                                Formatting.escapeMarkdown(removed.get().getAlbionPlayerName())));

        removeVerifiedRole(context, subject, embed);

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private void removeVerifiedRole(CommandContext context, Member subject, EmbedBuilder embed) {
        Long verifiedRoleId = context.config().getVerifiedRoleId();
        if (verifiedRoleId == null) {
            return;
        }
        Role role = context.guild().getRoleById(verifiedRoleId);
        if (role == null || !context.guild().getSelfMember().canInteract(role)) {
            return;
        }
        if (subject.getRoles().contains(role)) {
            context.guild().removeRoleFromMember(subject, role).reason("Unregistered").queue();
            embed.addField("Role removed", role.getAsMention(), false);
        }
    }
}
