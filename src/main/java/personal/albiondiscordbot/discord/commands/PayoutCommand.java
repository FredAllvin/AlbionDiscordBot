package personal.albiondiscordbot.discord.commands;

import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
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
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.service.BatchConfirmationService;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;

/**
 * {@code /payout user:@someone} or {@code /payout role:@role} — cashes members out.
 *
 * <p>This is silver leaving the ledger: the member is handed their balance in game and
 * the bot then clears it. It is the opposite of {@code /split}, which puts silver in.
 *
 * <p>There is no amount option. A cashout settles the whole debt, and the balance itself
 * is the amount — taking a number here would only invite it to disagree with the ledger.
 *
 * <p>The bot cannot watch an in-game trade, so the order matters: copy the list, send the
 * silver, <em>then</em> confirm. Confirming is an officer stating the transfer happened,
 * which is why the batch is reversible with {@code /undo} if it did not.
 */
@Component
public class PayoutCommand implements SlashCommand {

    private final BatchConfirmationService batches;

    public PayoutCommand(BatchConfirmationService batches) {
        this.batches = batches;
    }

    @Override
    public String name() {
        return "payout";
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
        return Commands.slash("payout", "Cash out — send balances in game, then clear them")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(
                        new OptionData(OptionType.USER, "user", "A single member to cash out", false),
                        new OptionData(OptionType.ROLE, "role", "Cash out everyone in a role", false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        Member member = event.getOption("user", OptionMapping::getAsMember);
        Role role = event.getOption("role", OptionMapping::getAsRole);

        if (member == null && role == null) {
            throw new CommandException(
                    "Pick who to cash out: `/payout user:@someone` for one person, "
                            + "or `/payout role:@role` for a whole group.");
        }
        if (member != null && role != null) {
            // Ambiguous rather than additive — better to ask than to guess and pay the
            // wrong set of people.
            throw new CommandException("Give either a user or a role, not both.");
        }

        String source;
        String sourceId;
        String label;
        List<Recipient> recipients;

        if (member != null) {
            source = BatchConfirmationService.SOURCE_USER;
            sourceId = member.getId();
            label = member.getAsMention();
            recipients = batches.resolveMember(context.guildId(), member);
        } else {
            source = BatchConfirmationService.SOURCE_ROLE;
            sourceId = role.getId();
            label = role.getAsMention();
            recipients = batches.resolveRole(context.guild(), role);
            if (recipients.isEmpty()) {
                throw new CommandException("%s has no members.".formatted(role.getAsMention()));
            }
        }

        // Say so now rather than after they have clicked Confirm on an empty preview.
        if (batches.totalOwed(context.guildId(), recipients) <= 0) {
            throw new CommandException(
                    member != null
                            ? "%s is owed nothing — their balance is already settled."
                                    .formatted(member.getAsMention())
                            : "Nobody in %s is owed anything.".formatted(role.getAsMention()));
        }

        event.getHook()
                .sendMessageEmbeds(batches.previewCashout(context.guildId(), recipients, label))
                .setComponents(ActionRow.of(batches.buttons(
                        BatchConfirmationService.OP_CASHOUT,
                        source,
                        sourceId,
                        // A cashout always clears the full balance, so no amount is encoded.
                        0L,
                        context.callerId())))
                .queue();
    }
}
