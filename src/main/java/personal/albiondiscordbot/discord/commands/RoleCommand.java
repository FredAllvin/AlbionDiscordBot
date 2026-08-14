package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.util.Formatting;

/**
 * {@code /role add <name> @user @user …} — tags an ad-hoc group, typically so
 * {@code /payout} can pay them all at once.
 *
 * <p>Slash commands cannot declare a variadic list of users, so {@code members} is a
 * single STRING option and the mentions are read back out of it with
 * {@code getMentions()}.
 */
@Component
public class RoleCommand implements SlashCommand {

    @Override
    public String name() {
        return "role";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("role", "Manage ad-hoc roles for payouts")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(new SubcommandData("add", "Create a role if needed and add mentioned members")
                        .addOptions(
                                new OptionData(OptionType.STRING, "name", "Role name, e.g. payout15", true)
                                        .setMaxLength(90),
                                new OptionData(
                                        OptionType.STRING,
                                        "members",
                                        "Mention everyone to add, e.g. @der @ber @ser",
                                        true)));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        if (!"add".equals(event.getSubcommandName())) {
            throw new CommandException("Unknown subcommand.");
        }
        if (!context.guild().getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            throw new CommandException("I need the **Manage Roles** permission to do that.");
        }

        String roleName = event.getOption("name", OptionMapping::getAsString).trim();
        OptionMapping membersOption = event.getOption("members");

        Set<Member> members = new LinkedHashSet<>(membersOption.getMentions().getMembers());
        members.removeIf(m -> m.getUser().isBot());
        if (members.isEmpty()) {
            throw new CommandException("Mention at least one member to add, e.g. `@someone @someoneelse`.");
        }

        Role role = findOrCreateRole(context, roleName);

        List<String> added = new ArrayList<>();
        List<String> alreadyHad = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (Member member : members) {
            if (member.getRoles().contains(role)) {
                alreadyHad.add(member.getEffectiveName());
                continue;
            }
            try {
                context.guild().addRoleToMember(member, role).reason("Added via /role add").complete();
                added.add(member.getEffectiveName());
            } catch (HierarchyException | InsufficientPermissionException e) {
                failed.add(member.getEffectiveName());
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Role " + Formatting.escapeMarkdown(role.getName()))
                .setColor(new Color(0x2ECC71))
                .setDescription("%s now has **%d** member%s."
                        .formatted(
                                role.getAsMention(),
                                role.getGuild().getMembersWithRoles(role).size(),
                                role.getGuild().getMembersWithRoles(role).size() == 1 ? "" : "s"));

        if (!added.isEmpty()) {
            embed.addField("Added (%d)".formatted(added.size()), truncate(added), false);
        }
        if (!alreadyHad.isEmpty()) {
            embed.addField("Already had it (%d)".formatted(alreadyHad.size()), truncate(alreadyHad), false);
        }
        if (!failed.isEmpty()) {
            embed.addField(
                    "Could not add (%d)".formatted(failed.size()),
                    truncate(failed) + "\nMy role must sit above " + role.getAsMention() + ".",
                    false);
        }
        embed.addField("Next", "Pay everyone with `/payout role:%s amount:<silver>`".formatted(role.getName()), false);

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private Role findOrCreateRole(CommandContext context, String roleName) {
        List<Role> existing = context.guild().getRolesByName(roleName, true);
        if (!existing.isEmpty()) {
            Role role = existing.get(0);
            if (!context.guild().getSelfMember().canInteract(role)) {
                throw new CommandException(
                        "The role **%s** already exists but sits above my highest role, so I cannot assign it. "
                                        .formatted(roleName)
                                + "Move my role above it in Server Settings → Roles.");
            }
            return role;
        }
        try {
            return context.guild()
                    .createRole()
                    .setName(roleName)
                    .setMentionable(true)
                    .reason("Created via /role add")
                    .complete();
        } catch (InsufficientPermissionException e) {
            throw new CommandException("I do not have permission to create roles here.");
        }
    }

    private String truncate(List<String> names) {
        String joined = String.join(", ", names);
        // Embed fields cap at 1024 characters.
        return joined.length() <= 1000 ? joined : joined.substring(0, 1000) + "…";
    }
}
