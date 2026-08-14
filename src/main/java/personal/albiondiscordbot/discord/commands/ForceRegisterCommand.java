package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
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
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.service.RegistrationService;
import personal.albiondiscordbot.util.Formatting;

/** {@code /force-register @user <name>} — staff override that skips guild verification. */
@Component
public class ForceRegisterCommand implements SlashCommand {

    private final RegistrationService registrationService;
    private final AuditLogService auditLog;

    public ForceRegisterCommand(RegistrationService registrationService, AuditLogService auditLog) {
        this.registrationService = registrationService;
        this.auditLog = auditLog;
    }

    @Override
    public String name() {
        return "force-register";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("force-register", "Register a member without verifying guild membership (staff)")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(
                        new OptionData(OptionType.USER, "user", "The member to register", true),
                        new OptionData(OptionType.STRING, "name", "Their in-game character name", true)
                                .setMaxLength(64));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        Member target = event.getOption("user", OptionMapping::getAsMember);
        if (target == null) {
            throw new CommandException("That user is not a member of this server.");
        }
        String characterName = event.getOption("name", OptionMapping::getAsString).trim();

        Registration registration = registrationService.forceRegister(
                context.guildId(), target.getIdLong(), characterName, context.callerId());

        auditLog.moderation(context, "%s force-registered as **%s** (guild membership NOT verified)".formatted(
                target.getAsMention(), Formatting.escapeMarkdown(registration.getAlbionPlayerName())));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Force-registered")
                .setColor(new Color(0xF1C40F))
                .setDescription("%s is now registered as **%s**."
                        .formatted(
                                target.getAsMention(),
                                Formatting.escapeMarkdown(registration.getAlbionPlayerName())))
                .addField(
                        "Unverified",
                        "Guild membership was not checked, so this registration is marked as forced in the audit log.",
                        false);

        if (registration.getAlbionPlayerId().startsWith("UNRESOLVED:")) {
            embed.addField(
                    "Character not found",
                    "No character with that exact name exists on EU, so `/stats` will not work for them "
                            + "until they are re-registered with a valid name.",
                    false);
        }

        Long verifiedRoleId = context.config().getVerifiedRoleId();
        if (verifiedRoleId != null) {
            Role role = context.guild().getRoleById(verifiedRoleId);
            if (role != null && context.guild().getSelfMember().canInteract(role)) {
                context.guild().addRoleToMember(target, role).reason("Force-registered by staff").queue();
                embed.addField("Role granted", role.getAsMention(), false);
            }
        }

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }
}
