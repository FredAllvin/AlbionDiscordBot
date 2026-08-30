package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.time.Instant;
import java.time.LocalTime;
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
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Objective;
import personal.albiondiscordbot.service.ObjectiveService;
import personal.albiondiscordbot.util.Formatting;
import personal.albiondiscordbot.util.UtcTimeParser;

/**
 * {@code /objective add|show} — the shared board of what pops when.
 *
 * <p>Times go in as {@code HH:MM} UTC, which is how Albion publishes them and how the
 * guild says them out loud. They come back out as Discord timestamps, which each person
 * reads in their own timezone with a countdown attached, so nobody has to do the
 * arithmetic that gets a group to a chest an hour late.
 *
 * <p>Everyone can add and everyone can read: objectives are intel, and intel only officers
 * may write down arrives too late to be worth having.
 */
@Component
public class ObjectiveCommand implements SlashCommand {

    /** Embed descriptions cap at 4096 characters. */
    private static final int DESCRIPTION_BUDGET = 3900;

    private final ObjectiveService objectives;
    private final Map<String, BiConsumer<SlashCommandInteractionEvent, CommandContext>> subcommands;

    public ObjectiveCommand(ObjectiveService objectives) {
        this.objectives = objectives;
        this.subcommands = Map.of(
                "add", this::add,
                "show", this::show);
    }

    @Override
    public String name() {
        return "objective";
    }

    /**
     * Both halves are guild news. An objective one person knows about is worth nothing —
     * the entire feature is telling everyone else — so neither the addition nor the board
     * is worth showing to the caller alone.
     */
    @Override
    public boolean ephemeral(SlashCommandInteractionEvent event) {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("objective", "Track what pops when, in UTC")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(
                        new SubcommandData("add", "Put an objective on the list")
                                .addOptions(
                                        new OptionData(
                                                        OptionType.STRING,
                                                        "name",
                                                        "What it is, e.g. Fort Sterling chest",
                                                        true)
                                                .setMaxLength(100),
                                        // One STRING and not two INTEGERs: the guild reads
                                        // "20:00" off the game clock and types it back in one
                                        // piece. No length bounds either — Discord would
                                        // refuse "9:30" with its own generic complaint, and
                                        // UtcTimeParser has a far better answer than that.
                                        new OptionData(
                                                OptionType.STRING,
                                                "time",
                                                "When it pops, UTC, HH:MM — e.g. 20:00",
                                                true)),
                        new SubcommandData("show", "List the objectives, soonest first"));
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

    private void add(SlashCommandInteractionEvent event, CommandContext context) {
        String name = event.getOption("name", OptionMapping::getAsString);
        LocalTime time = UtcTimeParser.parse(event.getOption("time", OptionMapping::getAsString));

        // One clock reading for the whole command, so the row that gets written and the
        // count reported next to it cannot disagree about what time it is.
        Instant now = Instant.now();
        Objective saved = objectives.add(context.guildId(), context.callerId(), name, time, now);
        int total = objectives.list(context.guildId(), now).size();

        long epoch = saved.getPopsAt().getEpochSecond();
        StringBuilder description = new StringBuilder();
        if (UtcTimeParser.isNextDay(saved.getPopsAt(), now)) {
            // 20:00 typed after 20:00 means tomorrow's. Said out loud, because the
            // timestamp beside it carries the date and a skim reads straight past it.
            description.append("That time has already gone by today, so this is **tomorrow's**.\n");
        }
        description.append("Pops <t:").append(epoch).append(":f> — <t:").append(epoch).append(":R>");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(Formatting.escapeMarkdown(saved.getName()))
                .setColor(new Color(0x2ECC71))
                .setDescription(description.toString())
                .addField("UTC", "`%s`".formatted(saved.popsAtUtc()), true)
                .addField("On the list", "%d objective%s".formatted(total, total == 1 ? "" : "s"), true)
                .setFooter("See them all with /objective show");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private void show(SlashCommandInteractionEvent event, CommandContext context) {
        Instant now = Instant.now();
        List<Objective> live = objectives.list(context.guildId(), now);

        if (live.isEmpty()) {
            event.getHook()
                    .sendMessage("Nothing on the list. Add one with `/objective add name:<what> time:<HH:MM>`.")
                    .queue();
            return;
        }

        StringBuilder body = new StringBuilder();
        int listed = 0;
        for (Objective objective : live) {
            long epoch = objective.getPopsAt().getEpochSecond();
            boolean popped = ObjectiveService.hasPopped(objective, now);
            // <t:…:R> reads "in 2 hours" ahead of time and "20 minutes ago" behind it, in
            // each viewer's own timezone. The UTC time is spelled out beside it anyway,
            // because that is the number the guild says to each other in comms.
            String line = "%s **%s** — %s<t:%d:R> · `%s` UTC\n"
                    .formatted(
                            popped ? "🔴" : "🕒",
                            Formatting.escapeMarkdown(objective.getName()),
                            popped ? "popped " : "",
                            epoch,
                            objective.popsAtUtc());

            if (body.length() + line.length() > DESCRIPTION_BUDGET) {
                body.append("…and %d more.".formatted(live.size() - listed));
                break;
            }
            body.append(line);
            listed++;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Objectives")
                .setColor(new Color(0x3498DB))
                .setDescription(body.toString())
                // Plain text: embed footers do not render markdown.
                .setFooter("%d on the list · they drop off %s after popping"
                        .formatted(live.size(), UtcTimeParser.humanize(ObjectiveService.GRACE)));

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }
}
