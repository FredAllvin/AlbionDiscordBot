package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
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
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.service.RegistrationService;
import personal.albiondiscordbot.util.Formatting;

/** {@code /register <ingame name>} — self-service, verified against the live API. */
@Component
public class RegisterCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(RegisterCommand.class);

    private final RegistrationService registrationService;

    public RegisterCommand(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @Override
    public String name() {
        return "register";
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

        Registration registration =
                registrationService.register(context.guildId(), context.callerId(), characterName);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Registered")
                .setColor(new Color(0x2ECC71))
                .setDescription("You are now registered as **%s**."
                        .formatted(Formatting.escapeMarkdown(registration.getAlbionPlayerName())));

        grantVerifiedRole(context, embed);

        event.getHook().sendMessageEmbeds(embed.build()).queue();
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
