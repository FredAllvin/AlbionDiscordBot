package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
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
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.service.BalanceService;
import personal.albiondiscordbot.util.Formatting;

/**
 * {@code /undo <batch>} — reverses a split or a cashout.
 *
 * <p>The batch id is printed in the footer of the message that created it. Reversal is
 * all-or-nothing and can only happen once per batch.
 */
@Component
public class UndoCommand implements SlashCommand {

    private final BalanceService balances;
    private final AuditLogService auditLog;

    public UndoCommand(BalanceService balances, AuditLogService auditLog) {
        this.balances = balances;
        this.auditLog = auditLog;
    }

    @Override
    public String name() {
        return "undo";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public boolean ephemeral(SlashCommandInteractionEvent event) {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("undo", "Reverse a split or cashout using the batch id from its message")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(new OptionData(
                                OptionType.STRING,
                                "batch",
                                "The batch id shown in the footer of the message",
                                true)
                        .setMaxLength(64));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        String batchId = event.getOption("batch", OptionMapping::getAsString).trim();

        BalanceService.UndoResult result = balances.undoBatch(context.guildId(), batchId, context.callerId());
        String kind = result.wasCashout() ? "cashout" : "split";

        auditLog.money(context, "Reversed %s batch `%s` — **%s** moved across %d member(s)"
                .formatted(kind, batchId, Formatting.silver(result.totalMoved()), result.reversedCount()));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Reversed the " + kind)
                .setColor(new Color(0xE74C3C))
                .setDescription(result.wasCashout()
                        ? "Put **%s** back onto the books for **%d** member%s — the guild owes it again."
                                .formatted(
                                        Formatting.silver(result.totalMoved()),
                                        result.reversedCount(),
                                        result.reversedCount() == 1 ? "" : "s")
                        : "Took back **%s** from **%d** member%s."
                                .formatted(
                                        Formatting.silver(result.totalMoved()),
                                        result.reversedCount(),
                                        result.reversedCount() == 1 ? "" : "s"))
                .setFooter("Batch " + result.batchId());

        if (!result.wentNegative().isEmpty()) {
            String mentions = result.wentNegative().stream()
                    .map(id -> "<@" + id + ">")
                    .collect(Collectors.joining(", "));
            embed.addField(
                    "Now in the negative (%d)".formatted(result.wentNegative().size()),
                    mentions
                            + "\nThey had already spent some of it, so their balance is below zero "
                            + "until they earn it back.",
                    false);
        }

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }
}
