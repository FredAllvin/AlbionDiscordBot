package personal.albiondiscordbot.discord.commands;

import java.util.List;
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
import personal.albiondiscordbot.repository.BattleRepository;
import personal.albiondiscordbot.service.BatchConfirmationService;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;
import personal.albiondiscordbot.util.SilverAmountParser;

/**
 * {@code /split-cta <amount> [battle]} — credits everyone who actually fought.
 *
 * <p>Reads the attendance the poller already recorded, so there is no {@code /role add}
 * step. With no battle id it uses the most recent CTA the bot tracked.
 *
 * <p>Attendance comes from the Albion API's battle roster, which only lists players who
 * scored a kill, died, or earned assist fame. Anyone who showed up and did nothing is
 * invisible to it, so the preview reports how many guild members fought versus how many
 * can actually be credited rather than pretending the list is complete.
 */
@Component
public class SplitCtaCommand implements SlashCommand {

    private final BatchConfirmationService batches;
    private final BattleRepository battles;

    public SplitCtaCommand(BatchConfirmationService batches, BattleRepository battles) {
        this.batches = batches;
        this.battles = battles;
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
                                "Battle id from the killboard post (defaults to the latest CTA)",
                                false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        long amountEach = SilverAmountParser.parse(event.getOption("amount", OptionMapping::getAsString));
        Battle battle = resolveBattle(event, context);

        List<Recipient> recipients = batches.resolveBattle(context.guildId(), battle.getAlbionBattleId());
        if (recipients.isEmpty()) {
            throw new CommandException(
                    "Nobody registered with this server appears in battle `%d`. "
                                    .formatted(battle.getAlbionBattleId())
                            + "Either they have not run `/register`, or none of them scored a kill, "
                            + "died or earned assist fame — which is all the Albion API records.");
        }

        long ourFighters = batches.countOurFighters(battle.getAlbionBattleId());
        long skipped = ourFighters - recipients.size();

        event.getHook()
                .sendMessageEmbeds(batches.previewSplit(
                        context.guildId(),
                        recipients,
                        amountEach,
                        "the %d-player fight `%d`"
                                .formatted(battle.getPlayerCount(), battle.getAlbionBattleId())))
                .setContent(
                        skipped > 0
                                // Compared against our own participation rows, not the battle
                                // total, which would count the enemy as unpaid guildmates.
                                ? "⚠️ %d of your guild fought but only %d are registered — the other %d cannot be credited."
                                        .formatted(ourFighters, recipients.size(), skipped)
                                : null)
                .setComponents(ActionRow.of(batches.buttons(
                        BatchConfirmationService.OP_SPLIT,
                        BatchConfirmationService.SOURCE_BATTLE,
                        Long.toString(battle.getAlbionBattleId()),
                        amountEach,
                        context.callerId())))
                .queue();
    }

    private Battle resolveBattle(SlashCommandInteractionEvent event, CommandContext context) {
        String battleId = event.getOption("battle", OptionMapping::getAsString);

        if (battleId == null || battleId.isBlank()) {
            return battles
                    .findLatestCta(
                            context.guildId(), context.ctaMinTotalPlayers(), context.ctaMinGuildPlayers())
                    .orElseThrow(() -> new CommandException(
                            "No CTA has been tracked yet, so there is nothing to credit. "
                                    + "Give a battle id, or wait for the poller to pick up a fight "
                                    + "matching the CTA rule (%s).".formatted(context.ctaRule())));
        }
        long parsed;
        try {
            parsed = Long.parseLong(battleId.trim());
        } catch (NumberFormatException e) {
            throw new CommandException(
                    "`%s` is not a battle id. Use the number from the killboard post, e.g. `417352406`."
                            .formatted(battleId));
        }
        return battles.findByAlbionBattleId(parsed)
                .orElseThrow(() -> new CommandException(
                        "Battle `%d` is not tracked. The bot only stores battles one of your guilds fought in."
                                .formatted(parsed)));
    }
}
