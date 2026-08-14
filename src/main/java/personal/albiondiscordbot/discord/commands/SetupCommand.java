package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandRegistrar;
import personal.albiondiscordbot.discord.PermissionService;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.service.GuildConfigService;

/** {@code /setup} — first-run configuration for a Discord server. */
@Component
public class SetupCommand implements SlashCommand {

    private final GuildConfigService guildConfigService;
    private final PermissionService permissionService;

    /**
     * Resolved lazily to break a cycle: the registrar depends on the registry, which
     * depends on every command, including this one. An {@code ObjectProvider} defers
     * the lookup to call time, when all three beans already exist.
     */
    private final ObjectProvider<CommandRegistrar> registrarProvider;

    public SetupCommand(
            GuildConfigService guildConfigService,
            PermissionService permissionService,
            ObjectProvider<CommandRegistrar> registrarProvider) {
        this.guildConfigService = guildConfigService;
        this.permissionService = permissionService;
        this.registrarProvider = registrarProvider;
    }

    @Override
    public String name() {
        return "setup";
    }

    @Override
    public boolean requiresSetup() {
        // Chicken and egg: this is the command that performs setup.
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("setup", "Configure the bot for this server")
                .setContexts(InteractionContextType.GUILD)
                // Hides the command from non-admins in the Discord UI. The real check
                // still happens server-side in execute().
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addOptions(
                        new OptionData(
                                OptionType.ROLE,
                                "staff_role",
                                "Role allowed to run balance, payout and moderation commands",
                                false),
                        new OptionData(
                                OptionType.ROLE,
                                "verified_role",
                                "Role granted to members who register an in-game name",
                                false),
                        new OptionData(
                                OptionType.CHANNEL, "log_channel", "Channel for audit logs", false),
                        new OptionData(
                                OptionType.CHANNEL,
                                "killboard_channel",
                                "Channel for automatic killboard posts of large battles",
                                false),
                        new OptionData(
                                        OptionType.INTEGER,
                                        "cta_min_total_players",
                                        "How big the whole fight must be to count as a CTA (default 30)",
                                        false)
                                .setMinValue(2)
                                .setMaxValue(1000),
                        new OptionData(
                                        OptionType.INTEGER,
                                        "cta_min_guild_players",
                                        "How many of YOUR guild must be in it (default 10). Both must be met.",
                                        false)
                                .setMinValue(1)
                                .setMaxValue(500));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        permissionService.requireAdministrator(context.member());

        DiscordGuildConfig config = guildConfigService.getOrCreate(context.guildId());

        Role staffRole = event.getOption("staff_role", OptionMapping::getAsRole);
        Role verifiedRole = event.getOption("verified_role", OptionMapping::getAsRole);
        TextChannel logChannel = asTextChannel(event.getOption("log_channel"));
        TextChannel killboardChannel = asTextChannel(event.getOption("killboard_channel"));
        Integer ctaMinTotal = event.getOption("cta_min_total_players", OptionMapping::getAsInt);
        Integer ctaMinGuild = event.getOption("cta_min_guild_players", OptionMapping::getAsInt);

        if (staffRole != null) {
            config.setStaffRoleId(staffRole.getIdLong());
        }
        if (verifiedRole != null) {
            config.setVerifiedRoleId(verifiedRole.getIdLong());
        }
        if (logChannel != null) {
            config.setLogChannelId(logChannel.getIdLong());
        }
        if (killboardChannel != null) {
            config.setKillboardChannelId(killboardChannel.getIdLong());
        }
        if (ctaMinTotal != null) {
            config.setCtaMinTotalPlayers(ctaMinTotal);
        }
        if (ctaMinGuild != null) {
            config.setCtaMinGuildPlayers(ctaMinGuild);
        }
        config.setSetupCompleted(true);
        guildConfigService.save(config);

        registrarProvider.getObject().register(context.guild());

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Setup complete")
                .setColor(new Color(0x2ECC71))
                .addField("Staff role", mentionRole(config.getStaffRoleId()), true)
                .addField("Verified role", mentionRole(config.getVerifiedRoleId()), true)
                .addField("Log channel", mentionChannel(config.getLogChannelId()), true)
                .addField("Killboard channel", mentionChannel(config.getKillboardChannelId()), true)
                .addField(
                        "Counts as a CTA",
                        "%d+ players in the fight\n**and** %d+ of our own"
                                .formatted(config.getCtaMinTotalPlayers(), config.getCtaMinGuildPlayers()),
                        true);

        StringBuilder next = new StringBuilder();
        if (config.getStaffRoleId() == null) {
            next.append("- No staff role set — only administrators can run staff commands.\n");
        }
        if (config.getVerifiedRoleId() == null) {
            next.append("- No verified role set — `/register` will not grant a role.\n");
        }
        if (config.getKillboardChannelId() == null) {
            next.append("- No killboard channel set — large battles will not be posted.\n");
        }
        next.append("- Run `/guild add <name>` to tell the bot which in-game guild counts as yours.");
        embed.addField("Next steps", next.toString(), false);

        if (verifiedRole != null && !context.guild().getSelfMember().canInteract(verifiedRole)) {
            embed.addField(
                    "Warning",
                    "My highest role is below %s, so I cannot assign it. Move my role above it in Server Settings → Roles."
                            .formatted(verifiedRole.getAsMention()),
                    false);
        }

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private TextChannel asTextChannel(OptionMapping option) {
        if (option == null) {
            return null;
        }
        return option.getAsChannel().asTextChannel();
    }

    private String mentionRole(Long roleId) {
        return roleId == null ? "_not set_" : "<@&" + roleId + ">";
    }

    private String mentionChannel(Long channelId) {
        return channelId == null ? "_not set_" : "<#" + channelId + ">";
    }
}
