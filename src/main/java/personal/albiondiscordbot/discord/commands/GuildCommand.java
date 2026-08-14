package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionGuildDetail;
import personal.albiondiscordbot.albion.dto.AlbionSearchResponse;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.util.Formatting;

/** {@code /guild add|remove|list} — manages which in-game guilds count as "ours". */
@Component
public class GuildCommand implements SlashCommand {

    private final AlbionApiClient albion;
    private final TrackedAlbionGuildRepository trackedGuilds;
    private final Map<String, BiConsumer<SlashCommandInteractionEvent, CommandContext>> subcommands;

    public GuildCommand(AlbionApiClient albion, TrackedAlbionGuildRepository trackedGuilds) {
        this.albion = albion;
        this.trackedGuilds = trackedGuilds;
        this.subcommands = Map.of(
                "add", this::add,
                "remove", this::remove,
                "list", this::list);
    }

    @Override
    public String name() {
        return "guild";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("guild", "Manage which in-game guilds this server tracks")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(
                        new SubcommandData("add", "Track an in-game guild")
                                .addOptions(new OptionData(
                                        OptionType.STRING, "name", "Exact in-game guild name", true)),
                        new SubcommandData("remove", "Stop tracking an in-game guild")
                                .addOptions(new OptionData(
                                        OptionType.STRING, "name", "Exact in-game guild name", true)),
                        new SubcommandData("list", "Show tracked in-game guilds"));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        BiConsumer<SlashCommandInteractionEvent, CommandContext> handler =
                subcommands.get(event.getSubcommandName());
        if (handler == null) {
            throw new CommandException("Unknown subcommand.");
        }
        handler.accept(event, context);
    }

    protected void add(SlashCommandInteractionEvent event, CommandContext context) {
        String name = event.getOption("name", OptionMapping::getAsString).trim();

        AlbionSearchResponse.GuildHit hit = albion.findGuildByExactName(name)
                .orElseThrow(() -> new CommandException(
                        "No guild called **%s** exists on the EU server. The name must match exactly."
                                .formatted(name)));

        if (trackedGuilds.existsByDiscordGuildIdAndAlbionGuildId(context.guildId(), hit.id())) {
            throw new CommandException("**%s** is already tracked.".formatted(hit.name()));
        }

        TrackedAlbionGuild tracked =
                new TrackedAlbionGuild(context.guildId(), hit.id(), hit.name(), context.callerId());

        AlbionGuildDetail detail = albion.getGuild(hit.id()).orElse(null);
        if (detail != null) {
            tracked.setAllianceId(detail.allianceId());
            tracked.setAllianceTag(detail.allianceTag());
        }
        trackedGuilds.save(tracked);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Now tracking " + Formatting.escapeMarkdown(hit.name()))
                .setColor(new Color(0x2ECC71))
                .addField("Guild id", "`" + hit.id() + "`", false);
        if (detail != null && detail.memberCount() != null) {
            embed.addField("Members", detail.memberCount().toString(), true);
        }
        if (detail != null && detail.allianceName() != null && !detail.allianceName().isBlank()) {
            embed.addField("Alliance", Formatting.escapeMarkdown(detail.allianceName()), true);
        }
        embed.setDescription("Members of this guild can now use `/register`.");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    protected void remove(SlashCommandInteractionEvent event, CommandContext context) {
        String name = event.getOption("name", OptionMapping::getAsString).trim();

        TrackedAlbionGuild tracked = trackedGuilds.findByDiscordGuildId(context.guildId()).stream()
                .filter(t -> t.getAlbionGuildName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new CommandException("**%s** is not tracked.".formatted(name)));

        trackedGuilds.delete(tracked);

        event.getHook()
                .sendMessage("No longer tracking **%s**. Existing registrations are unaffected."
                        .formatted(Formatting.escapeMarkdown(tracked.getAlbionGuildName())))
                .queue();
    }

    protected void list(SlashCommandInteractionEvent event, CommandContext context) {
        List<TrackedAlbionGuild> tracked = trackedGuilds.findByDiscordGuildId(context.guildId());

        if (tracked.isEmpty()) {
            event.getHook()
                    .sendMessage("No in-game guilds are tracked yet. Add one with `/guild add <name>`.")
                    .queue();
            return;
        }

        StringBuilder body = new StringBuilder();
        for (TrackedAlbionGuild guild : tracked) {
            body.append("- **")
                    .append(Formatting.escapeMarkdown(guild.getAlbionGuildName()))
                    .append("**");
            if (guild.getAllianceTag() != null && !guild.getAllianceTag().isBlank()) {
                body.append(" [").append(Formatting.escapeMarkdown(guild.getAllianceTag())).append("]");
            }
            body.append("\n");
        }

        event.getHook()
                .sendMessageEmbeds(new EmbedBuilder()
                        .setTitle("Tracked in-game guilds")
                        .setColor(new Color(0x3498DB))
                        .setDescription(body.toString())
                        .build())
                .queue();
    }
}
