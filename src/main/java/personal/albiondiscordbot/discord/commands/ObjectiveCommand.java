package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.AuditLogService;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Objective;
import personal.albiondiscordbot.service.ObjectiveService;
import personal.albiondiscordbot.util.Formatting;
import personal.albiondiscordbot.util.UtcTimeParser;

/**
 * {@code /objective add|show|edit|remove} — the shared board of what pops when.
 *
 * <p>Times go in as {@code HH:MM} UTC, which is how Albion publishes them and how the
 * guild says them out loud. They come back out as Discord timestamps, which each person
 * reads in their own timezone with a countdown attached, so nobody has to do the
 * arithmetic that gets a group to a chest an hour late.
 *
 * <p>Everyone can add and everyone can read: objectives are intel, and intel only officers
 * may write down arrives too late to be worth having. Everyone can correct and remove for
 * the same reason — a line that is wrong is worse than no line, and whoever notices is
 * rarely whoever typed it. Both of those go to the audit log, which is where the record of
 * who moved what lives once the message has scrolled away.
 *
 * <p>{@code edit} picks from the live board — the option autocompletes against what is
 * actually up, so there is no name to spell and no tie to break. {@code remove} names a
 * line the way the guild does, by what it is called, with {@code time:} and {@code zone:}
 * for when that name is up more than once. Neither one ever picks on the caller's behalf.
 */
@Component
public class ObjectiveCommand implements SlashCommand {

    /** Embed descriptions cap at 4096 characters. */
    private static final int DESCRIPTION_BUDGET = 3900;

    /**
     * Types a zone away, since Discord cannot send an option that is empty.
     *
     * <p>Without it a zone could be corrected but never taken back off, and the only way
     * to lose one would be to remove the objective and add it again.
     */
    private static final String CLEAR_ZONE = "-";

    /**
     * Marks a value as one the picker produced rather than one somebody typed.
     *
     * <p>Never seen — Discord shows the choice's label and sends its value — and it is
     * what lets a row id and a hand-typed name share one option without either being
     * mistaken for the other.
     */
    private static final String PICKED = "id:";

    private final ObjectiveService objectives;
    private final AuditLogService auditLog;
    private final Map<String, BiConsumer<SlashCommandInteractionEvent, CommandContext>> subcommands;

    public ObjectiveCommand(ObjectiveService objectives, AuditLogService auditLog) {
        this.objectives = objectives;
        this.auditLog = auditLog;
        this.subcommands = Map.of(
                "add", this::add,
                "show", this::show,
                "edit", this::edit,
                "remove", this::remove);
    }

    @Override
    public String name() {
        return "objective";
    }

    /**
     * All four are guild news. An objective one person knows about is worth nothing — the
     * entire feature is telling everyone else — so neither the addition nor the board is
     * worth showing to the caller alone.
     *
     * <p>Corrections included, and especially the removals: somebody already on their way
     * to a chest that has just been called off has to hear about it in the channel they
     * read it in. That is the opposite of the call {@code /balance remove} makes, and for
     * the opposite reason — silver coming back is settled by the ledger, while an
     * objective coming down is only settled by everyone knowing.
     */
    @Override
    public boolean ephemeral(SlashCommandInteractionEvent event) {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        // One STRING and not two INTEGERs: the guild reads "20:00" off the game clock and
        // types it back in one piece. No length bounds either — Discord would refuse
        // "9:30" with its own generic complaint, and UtcTimeParser has a far better
        // answer than that.
        OptionData popsAt = new OptionData(
                OptionType.STRING, "time", "When it pops, UTC, HH:MM — e.g. 20:00", true);
        OptionData zone = new OptionData(
                        OptionType.STRING, "zone", "Where it is, e.g. Fort Sterling — optional", false)
                .setMaxLength(60);

        // /objective edit picks from the live board. Autocomplete rather than choices:
        // choices are fixed when the command is registered and the board turns over
        // hourly, so the list has to be built per keystroke against what is up now.
        OptionData pick = new OptionData(
                        OptionType.STRING, "objective", "Which one — pick it from the list", true)
                .setAutoComplete(true)
                .setMaxLength(100);

        // /objective remove has no picker, so it names the line the old way. Optional and
        // rarely typed: a name is enough until the same name is on the board twice, which
        // is exactly when these say which of them is meant.
        OptionData whichName = new OptionData(
                        OptionType.STRING, "name", "Which objective — the name as it is on the list", true)
                .setMaxLength(100);
        OptionData whichTime = new OptionData(
                OptionType.STRING, "time", "Only if that name is up more than once — its UTC time, HH:MM", false);
        OptionData whichZone = new OptionData(
                        OptionType.STRING, "zone", "Only if that name is up more than once — its zone", false)
                .setMaxLength(60);

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
                                        popsAt,
                                        zone),
                        new SubcommandData("show", "List the objectives, soonest first"),
                        new SubcommandData("edit", "Fix an objective that is on the list")
                                .addOptions(
                                        pick,
                                        new OptionData(OptionType.STRING, "new_name", "Rename it", false)
                                                .setMaxLength(100),
                                        new OptionData(
                                                OptionType.STRING,
                                                "new_time",
                                                "Move it to this UTC time, HH:MM — e.g. 21:00",
                                                false),
                                        new OptionData(
                                                        OptionType.STRING,
                                                        "new_zone",
                                                        "Set the zone, or " + CLEAR_ZONE + " to take it off",
                                                        false)
                                                .setMaxLength(60)),
                        new SubcommandData("remove", "Take an objective off the list")
                                .addOptions(whichName, whichTime, whichZone));
    }

    /**
     * The live board, as the {@code /objective edit} picker.
     *
     * <p>Filtered here rather than by Discord, which only narrows a static choice list and
     * has none to narrow. Matching runs over the whole label, so "martlock" finds the
     * chest there and "20:" finds everything at eight.
     *
     * <p>Reads through {@link ObjectiveService#visible} rather than the sweeping list: this
     * fires on every keystroke, and the sweep is a write.
     */
    @Override
    public List<Command.Choice> autocomplete(CommandAutoCompleteInteractionEvent event) {
        if (!"objective".equals(event.getFocusedOption().getName())) {
            return List.of();
        }
        Instant now = Instant.now();
        String typed = event.getFocusedOption().getValue().trim().toLowerCase(Locale.ROOT);

        List<Command.Choice> choices = new ArrayList<>();
        for (Objective objective : objectives.visible(event.getGuild().getIdLong(), now)) {
            String label = label(objective, now);
            if (!typed.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(typed)) {
                continue;
            }
            choices.add(new Command.Choice(label, PICKED + objective.getId()));
            if (choices.size() == OptionData.MAX_CHOICES) {
                break;
            }
        }
        return choices;
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
        String zone = event.getOption("zone", OptionMapping::getAsString);
        LocalTime time = UtcTimeParser.parse(event.getOption("time", OptionMapping::getAsString));

        // One clock reading for the whole command, so the row that gets written and the
        // count reported next to it cannot disagree about what time it is.
        Instant now = Instant.now();
        Objective saved = objectives.add(context.guildId(), context.callerId(), name, zone, time, now);
        int total = objectives.list(context.guildId(), now).size();

        EmbedBuilder embed = card(saved, now, new Color(0x2ECC71))
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
            String line = "%s **%s**%s — %s<t:%d:R> · `%s` UTC\n"
                    .formatted(
                            popped ? "🔴" : "🕒",
                            Formatting.escapeMarkdown(objective.getName()),
                            objective.getZone() == null
                                    ? ""
                                    : " (%s)".formatted(Formatting.escapeMarkdown(objective.getZone())),
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

    private void edit(SlashCommandInteractionEvent event, CommandContext context) {
        Instant now = Instant.now();
        ObjectiveService.Change change = new ObjectiveService.Change(
                event.getOption("new_name", OptionMapping::getAsString),
                optionalTime(event, "new_time"),
                newZone(event));

        ObjectiveService.Edited edited = objectives.edit(context.guildId(), picked(event), change, now);
        Objective objective = edited.objective();

        // One line per thing that moved, old on the left. People reading this already saw
        // the objective go up, so what they are looking for is the difference.
        List<String> changes = new ArrayList<>();
        if (!objective.getName().equals(edited.previousName())) {
            changes.add("Name — %s → %s"
                    .formatted(
                            Formatting.escapeMarkdown(edited.previousName()),
                            Formatting.escapeMarkdown(objective.getName())));
        }
        if (!objective.getPopsAt().equals(edited.previousPopsAt())) {
            changes.add("Time — `%s` → `%s` UTC"
                    .formatted(UtcTimeParser.timeOf(edited.previousPopsAt()), objective.popsAtUtc()));
        }
        if (!Objects.equals(objective.getZone(), edited.previousZone())) {
            changes.add("Zone — %s → %s"
                    .formatted(zoneOrNone(edited.previousZone()), zoneOrNone(objective.getZone())));
        }

        EmbedBuilder embed = card(objective, now, new Color(0xF1C40F))
                .addField("Changed", String.join("\n", changes), false)
                .setFooter("See them all with /objective show");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
        auditLog.objective(
                context,
                "Edited **%s** on the objective list.\n%s"
                        .formatted(Formatting.escapeMarkdown(objective.getName()), String.join("\n", changes)));
    }

    private void remove(SlashCommandInteractionEvent event, CommandContext context) {
        Instant now = Instant.now();
        Objective gone = objectives.remove(context.guildId(), selector(event), now);
        int left = objectives.list(context.guildId(), now).size();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(Formatting.escapeMarkdown(gone.getName()))
                .setColor(new Color(0xE74C3C))
                // Past tense, and the time it was set for. Somebody reading this may
                // already be walking to it, and what they need first is to know whether
                // this is the one they had in mind.
                .setDescription("Taken off the list — it was set for <t:%d:f>."
                        .formatted(gone.getPopsAt().getEpochSecond()))
                .addField("UTC", "`%s`".formatted(gone.popsAtUtc()), true);
        if (gone.getZone() != null) {
            embed.addField("Zone", Formatting.escapeMarkdown(gone.getZone()), true);
        }
        embed.addField("Still on the list", "%d objective%s".formatted(left, left == 1 ? "" : "s"), true)
                .setFooter("See what is left with /objective show");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
        auditLog.objective(
                context,
                "Removed **%s** (%s) from the objective list."
                        .formatted(Formatting.escapeMarkdown(gone.getName()), ObjectiveService.slot(gone)));
    }

    /** The shared head of an add and an edit reply: what it is, and when it pops. */
    private EmbedBuilder card(Objective objective, Instant now, Color color) {
        long epoch = objective.getPopsAt().getEpochSecond();
        StringBuilder description = new StringBuilder();
        if (UtcTimeParser.isNextDay(objective.getPopsAt(), now)) {
            // 20:00 typed after 20:00 means tomorrow's. Said out loud, because the
            // timestamp beside it carries the date and a skim reads straight past it.
            description.append("That time has already gone by today, so this is **tomorrow's**.\n");
        }
        description.append("Pops <t:").append(epoch).append(":f> — <t:").append(epoch).append(":R>");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(Formatting.escapeMarkdown(objective.getName()))
                .setColor(color)
                .setDescription(description.toString())
                .addField("UTC", "`%s`".formatted(objective.popsAtUtc()), true);
        if (objective.getZone() != null) {
            embed.addField("Zone", Formatting.escapeMarkdown(objective.getZone()), true);
        }
        return embed;
    }

    /**
     * One line as the picker lists it — the board's own vocabulary, so what you choose
     * reads like the line you were looking at.
     *
     * <p>A choice name caps at 100 characters and a name and a zone can spend 160 between
     * them. The time is the part that has to survive, since it is what tells two lines of
     * the same name apart, so the name gives way first.
     */
    static String label(Objective objective, Instant now) {
        boolean popped = ObjectiveService.hasPopped(objective, now);
        String head = popped ? "🔴 " : "🕒 ";

        StringBuilder tail = new StringBuilder();
        if (objective.getZone() != null) {
            tail.append(" · ").append(objective.getZone());
        }
        tail.append(" · ").append(objective.popsAtUtc()).append(" UTC");
        if (popped) {
            tail.append(" · popped");
        }

        String name = objective.getName();
        int room = Command.Choice.MAX_NAME_LENGTH - head.length() - tail.length();
        if (name.length() > room) {
            name = name.substring(0, Math.max(1, room - 1)) + "…";
        }
        String label = head + name + tail;
        // Belt and braces: Command.Choice throws on an over-long name, and an exception
        // inside an autocomplete leaves the caller watching a box that never fills in.
        return label.length() <= Command.Choice.MAX_NAME_LENGTH
                ? label
                : label.substring(0, Command.Choice.MAX_NAME_LENGTH);
    }

    /**
     * Which objective an {@code edit} means.
     *
     * <p>The picker sends back a marked row id, which names one line and cannot be
     * ambiguous. Anything else is what somebody typed instead of picking — Discord lets an
     * autocompleting option be submitted with whatever was in the box — and falls back to
     * matching on the name, the way {@code /objective remove} works. The marker is what
     * keeps those apart, so an objective somebody called "42" is still found by its name.
     */
    private static ObjectiveService.Selector picked(SlashCommandInteractionEvent event) {
        String value = event.getOption("objective", OptionMapping::getAsString).trim();
        if (value.startsWith(PICKED)) {
            try {
                return ObjectiveService.Selector.picked(Long.parseLong(value.substring(PICKED.length())));
            } catch (NumberFormatException e) {
                // Typed, and it happened to start with the marker. Treat it as a name.
            }
        }
        return ObjectiveService.Selector.typed(value, null, null);
    }

    private static ObjectiveService.Selector selector(SlashCommandInteractionEvent event) {
        return ObjectiveService.Selector.typed(
                event.getOption("name", OptionMapping::getAsString),
                optionalTime(event, "time"),
                event.getOption("zone", OptionMapping::getAsString));
    }

    /** An {@code HH:MM} option that was allowed to be left out, or {@code null} if it was. */
    private static LocalTime optionalTime(SlashCommandInteractionEvent event, String option) {
        String raw = event.getOption(option, OptionMapping::getAsString);
        return raw == null ? null : UtcTimeParser.parse(raw);
    }

    /** The zone an edit is setting, where {@link #CLEAR_ZONE} means take it off. */
    private static String newZone(SlashCommandInteractionEvent event) {
        String raw = event.getOption("new_zone", OptionMapping::getAsString);
        if (raw == null) {
            return null;
        }
        // Blank is how the service spells "none", which is a value Discord will not send.
        return CLEAR_ZONE.equals(raw.trim()) ? "" : raw;
    }

    /** Italic when there is no zone, so an empty one cannot read as a place called "none". */
    private static String zoneOrNone(String zone) {
        return zone == null ? "*none*" : Formatting.escapeMarkdown(zone);
    }
}
