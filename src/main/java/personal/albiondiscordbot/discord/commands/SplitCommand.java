package personal.albiondiscordbot.discord.commands;

import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
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
import personal.albiondiscordbot.util.SilverAmountParser;

/**
 * {@code /split @role <amount>} — credits a share of loot to <strong>each</strong>
 * member's balance.
 *
 * <p>Silver goes <em>into</em> the ledger here; the guild now owes it. Handing it over
 * in game is {@code /payout}.
 *
 * <p>The amount is per person, not a total to divide: a role of 15 paid 1,000,000 adds
 * 15,000,000 to the books.
 */
@Component
public class SplitCommand implements SlashCommand {

    private final BatchConfirmationService batches;

    public SplitCommand(BatchConfirmationService batches) {
        this.batches = batches;
    }

    @Override
    public String name() {
        return "split";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public boolean ephemeral(SlashCommandInteractionEvent event) {
        // The preview is private so nobody else can click the buttons; the confirmed
        // result is announced publicly afterwards.
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("split", "Credit a share of loot to every member of a role")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(
                        new OptionData(OptionType.ROLE, "role", "The role to credit", true),
                        new OptionData(
                                OptionType.STRING,
                                "amount",
                                "Silver PER PERSON, e.g. 1m, 500k or 1000000",
                                true));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        Role role = event.getOption("role", OptionMapping::getAsRole);
        long amountEach = SilverAmountParser.parse(event.getOption("amount", OptionMapping::getAsString));

        List<Recipient> recipients = batches.resolveRole(context.guild(), role);
        if (recipients.isEmpty()) {
            throw new CommandException(
                    "%s has no members. Add some with `/role add %s @user …`."
                            .formatted(role.getAsMention(), role.getName()));
        }

        event.getHook()
                .sendMessageEmbeds(batches.previewSplit(
                        context.guildId(), recipients, amountEach, role.getAsMention()))
                .setComponents(ActionRow.of(batches.buttons(
                        BatchConfirmationService.OP_SPLIT,
                        BatchConfirmationService.SOURCE_ROLE,
                        role.getId(),
                        amountEach,
                        context.callerId())))
                .queue();
    }
}
