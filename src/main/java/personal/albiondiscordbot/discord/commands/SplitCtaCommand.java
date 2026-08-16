package personal.albiondiscordbot.discord.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Battle;
import personal.albiondiscordbot.service.BatchConfirmationService;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;
import personal.albiondiscordbot.service.BattleLookupService;
import personal.albiondiscordbot.util.BattleIdParser;
import personal.albiondiscordbot.util.SilverAmountParser;

/**
 * {@code /split-cta <amount> [battle…]} — credits everyone who actually fought.
 *
 * <p>Reads the attendance the poller already recorded, so there is no {@code /role add}
 * step. With no battle id it uses the most recent CTA the bot tracked.
 *
 * <p><strong>One CTA is often several battles.</strong> The fight breaks off, everyone
 * reforms, and Albion opens a new battle id — which is why the killboard the guild reads
 * merges them at {@code albionbb.com/battles/417352406,417352999}. Name them all in one
 * split, comma-separated, and everyone who was in <em>any</em> of them is credited
 * <em>once</em>. Splitting them one at a time instead pays whoever stayed for the whole
 * CTA three times over and the person who came for one fight once, which is backwards.
 *
 * <p>Attendance comes from the Albion API's battle roster, which only lists players who
 * scored a kill, died, or earned assist fame. Anyone who showed up and did nothing is
 * invisible to it, so the preview reports how many guild members fought versus how many
 * can actually be credited rather than pretending the list is complete.
 */
@Component
public class SplitCtaCommand implements SlashCommand {

    private final BatchConfirmationService batches;
    private final BattleLookupService battleLookup;

    public SplitCtaCommand(BatchConfirmationService batches, BattleLookupService battleLookup) {
        this.batches = batches;
        this.battleLookup = battleLookup;
    }

    @Override
    public String name() {
        return "split-cta";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public boolean ephemeral() {
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("split-cta", "Credit everyone who fought in a tracked battle")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(
                        new OptionData(
                                OptionType.STRING,
                                "amount",
                                "Silver PER PERSON, e.g. 1m, 500k or 1000000",
                                true),
                        new OptionData(
                                OptionType.STRING,
                                "battle",
                                "Battle id, or several separated by commas (defaults to the latest CTA)",
                                false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        long amountEach = SilverAmountParser.parse(event.getOption("amount", OptionMapping::getAsString));
        List<Battle> battles = resolveBattles(event, context);
        List<Long> battleIds = battles.stream().map(Battle::getAlbionBattleId).toList();

        List<Recipient> recipients = batches.resolveBattles(context.guildId(), battleIds);
        if (recipients.isEmpty()) {
            throw new CommandException(
                    "Nobody registered with this server appears in %s. ".formatted(idsFor(battleIds))
                            + "Either they have not run `/register`, or none of them scored a kill, "
                            + "died or earned assist fame — which is all the Albion API records.");
        }

        long ourFighters = batches.countOurFighters(battleIds);
        long skipped = ourFighters - recipients.size();

        // Written down before the preview goes out, because the buttons carry only the key.
        String groupKey = batches.rememberBattles(context.guildId(), battleIds);

        event.getHook()
                .sendMessageEmbeds(
                        batches.previewSplit(context.guildId(), recipients, amountEach, describe(battles)))
                .setContent(notes(battleIds.size(), ourFighters, recipients.size(), skipped))
                .setComponents(ActionRow.of(batches.buttons(
                        BatchConfirmationService.OP_SPLIT,
                        BatchConfirmationService.SOURCE_BATTLES,
                        groupKey,
                        amountEach,
                        context.callerId())))
                .queue();
    }

    /**
     * The line above the preview. The merge note leads because it is the thing an officer
     * has to trust before confirming a split covering more than one killboard.
     */
    private String notes(int battleCount, long ourFighters, int credited, long skipped) {
        List<String> notes = new ArrayList<>(2);
        if (battleCount > 1) {
            notes.add("Merging **%d** fights — anyone who was in more than one is still paid **once**."
                    .formatted(battleCount));
        }
        if (skipped > 0) {
            // Compared against our own participation rows, not the battle total, which
            // would count the enemy as unpaid guildmates.
            notes.add("⚠️ %d of your guild fought but only %d are registered — the other %d cannot be credited."
                    .formatted(ourFighters, credited, skipped));
        }
        return notes.isEmpty() ? null : String.join("\n", notes);
    }

    /** How the preview names what is being paid for. */
    private String describe(List<Battle> battles) {
        if (battles.size() == 1) {
            Battle battle = battles.get(0);
            return "the %d-player fight `%d`".formatted(battle.getPlayerCount(), battle.getAlbionBattleId());
        }
        return "%d fights of one CTA (%s)"
                .formatted(
                        battles.size(),
                        battles.stream()
                                .map(b -> "`%d`".formatted(b.getAlbionBattleId()))
                                .collect(Collectors.joining(", ")));
    }

    private String idsFor(List<Long> battleIds) {
        if (battleIds.size() == 1) {
            return "battle `%d`".formatted(battleIds.get(0));
        }
        return "any of the %d fights (%s)"
                .formatted(
                        battleIds.size(),
                        battleIds.stream().map("`%d`"::formatted).collect(Collectors.joining(", ")));
    }

    private List<Battle> resolveBattles(SlashCommandInteractionEvent event, CommandContext context) {
        String battleOption = event.getOption("battle", OptionMapping::getAsString);

        if (battleOption == null || battleOption.isBlank()) {
            return List.of(battleLookup
                    .latestCta(context.guildId(), context.ctaMinTotalPlayers(), context.ctaMinGuildPlayers())
                    .orElseThrow(() -> new CommandException(
                            "No CTA has been tracked yet, so there is nothing to credit. "
                                    + "Give a battle id, or wait for the poller to pick up a fight "
                                    + "matching the CTA rule (%s).".formatted(context.ctaRule()))));
        }

        // Each id is looked up, and fetched from the Albion API if the poller never saw it
        // — which is the difference between "we have no record of that fight" and "your
        // guild was not in it". Only the second is a reason to refuse.
        return BattleIdParser.parse(battleOption).stream()
                .map(id -> battleLookup.require(context.guildId(), id))
                .toList();
    }
}
