package personal.albiondiscordbot.discord;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.domain.Battle;
import personal.albiondiscordbot.service.BalanceService;
import personal.albiondiscordbot.service.BatchConfirmationService;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;
import personal.albiondiscordbot.util.Formatting;

/**
 * Confirm / Deny / Copy for a pending split or cashout.
 *
 * <p>Custom id: {@code bt:<action>:<op>:<source>:<sourceId>:<amount>:<invokerId>}.
 */
@Component
public class BatchButtonHandler implements ButtonHandler {

    /** Discord messages cap at 2000 characters; longer lists go out as a file. */
    private static final int INLINE_COPY_LIMIT = 1850;

    private final BatchConfirmationService batches;
    private final AuditLogService auditLog;

    public BatchButtonHandler(BatchConfirmationService batches, AuditLogService auditLog) {
        this.batches = batches;
        this.auditLog = auditLog;
    }

    @Override
    public String prefix() {
        return BatchConfirmationService.BUTTON_PREFIX;
    }

    @Override
    public void handle(ButtonInteractionEvent event, String[] args, CommandContext context) {
        if (args.length < 6) {
            throw new CommandException("That button is malformed.");
        }
        String action = args[0];
        String op = args[1];
        String source = args[2];
        String sourceId = args[3];
        long amountEach = Long.parseLong(args[4]);
        long invokerId = Long.parseLong(args[5]);

        // Only whoever ran the command may resolve it. Staff permission alone is not
        // enough: two officers each previewing a batch must not confirm each other's.
        if (context.callerId() != invokerId) {
            throw new CommandException("Only <@%d> can confirm this.".formatted(invokerId));
        }

        switch (action) {
            case "ok" -> confirm(event, context, op, source, sourceId, amountEach, invokerId);
            case "no" -> deny(event, op);
            case "cp" -> copy(event, context, source, sourceId);
            default -> throw new CommandException("Unknown button action.");
        }
    }

    private void confirm(
            ButtonInteractionEvent event,
            CommandContext context,
            String op,
            String source,
            String sourceId,
            long amountEach,
            long invokerId) {

        // Membership is re-resolved rather than carried from the preview: the role or the
        // registrations may have changed, and acting on the current set is what the
        // officer means. The result states what actually happened.
        List<Recipient> recipients = resolve(context, source, sourceId);
        if (recipients.isEmpty()) {
            throw new CommandException("There is nobody left in that group.");
        }
        String label = sourceLabel(context, source, sourceId);

        MessageEmbed done;
        MessageEmbed announcement;

        if (BatchConfirmationService.OP_CASHOUT.equals(op)) {
            BalanceService.CashoutResult result =
                    batches.executeCashout(context.guildId(), recipients, context.callerId(), label);

            auditLog.money(context, "Cashed out **%s** to %d member(s) of %s (batch %s)"
                    .formatted(
                            Formatting.silver(result.totalPaid()),
                            result.paidCount(),
                            label,
                            result.batchId()));

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Cashout recorded")
                    .setColor(new Color(0x2ECC71))
                    .setDescription("Cleared **%s** across **%d** member%s of %s. Their balances are now zero."
                            .formatted(
                                    Formatting.silver(result.totalPaid()),
                                    result.paidCount(),
                                    result.paidCount() == 1 ? "" : "s",
                                    label))
                    .addField("Total paid out", Formatting.silver(result.totalPaid()), true)
                    .addField("Members", Integer.toString(result.paidCount()), true)
                    .setFooter("Batch " + result.batchId() + " · reverse with /undo");

            if (!result.skipped().isEmpty()) {
                embed.addField(
                        "Skipped",
                        "%d were owed nothing.".formatted(result.skipped().size()),
                        true);
            }
            done = embed.build();

            announcement = new EmbedBuilder()
                    .setTitle("Cashout")
                    .setColor(new Color(0x2ECC71))
                    .setDescription("%s paid out **%s** in game to **%d** member%s of %s."
                            .formatted(
                                    event.getMember().getAsMention(),
                                    Formatting.silver(result.totalPaid()),
                                    result.paidCount(),
                                    result.paidCount() == 1 ? "" : "s",
                                    label))
                    .setFooter("Batch " + result.batchId())
                    .build();
        } else {
            BalanceService.SplitResult result = batches.executeSplit(
                    context.guildId(), recipients, amountEach, context.callerId(), label);

            auditLog.money(context, "Split — **%s** each to %d member(s) of %s, total **%s** (batch %s)"
                    .formatted(
                            Formatting.silver(result.amountEach()),
                            result.recipientCount(),
                            label,
                            Formatting.silver(result.totalCredited()),
                            result.batchId()));

            done = new EmbedBuilder()
                    .setTitle("Split credited")
                    .setColor(new Color(0x2ECC71))
                    .setDescription("Credited **%s** to **each** of %d member%s of %s."
                            .formatted(
                                    Formatting.silver(result.amountEach()),
                                    result.recipientCount(),
                                    result.recipientCount() == 1 ? "" : "s",
                                    label))
                    .addField("Total credited", Formatting.silver(result.totalCredited()), true)
                    .addField("Members", Integer.toString(result.recipientCount()), true)
                    .setFooter("Batch " + result.batchId() + " · reverse with /undo")
                    .build();

            announcement = new EmbedBuilder()
                    .setTitle("Split")
                    .setColor(new Color(0x2ECC71))
                    .setDescription("%s credited **%s** to each of **%d** member%s of %s."
                            .formatted(
                                    event.getMember().getAsMention(),
                                    Formatting.silver(result.amountEach()),
                                    result.recipientCount(),
                                    result.recipientCount() == 1 ? "" : "s",
                                    label))
                    .setFooter("Batch " + result.batchId())
                    .build();
        }

        event.getHook()
                .editOriginal(MessageEditData.fromEmbeds(done))
                .setComponents(ActionRow.of(
                        batches.copyButtonOnly(op, source, sourceId, amountEach, invokerId)))
                .queue();

        // The preview is ephemeral, so announce the result where the guild can see it.
        event.getChannel().sendMessageEmbeds(announcement).queue();
    }

    private void deny(ButtonInteractionEvent event, String op) {
        boolean cashout = BatchConfirmationService.OP_CASHOUT.equals(op);
        event.getHook()
                .editOriginal(MessageEditData.fromEmbeds(new EmbedBuilder()
                        .setTitle(cashout ? "Cashout cancelled" : "Split cancelled")
                        .setColor(new Color(0x95A5A6))
                        .setDescription(cashout
                                ? "No balances were cleared."
                                : "No silver was credited.")
                        .build()))
                .setComponents()
                .queue();
    }

    private void copy(
            ButtonInteractionEvent event, CommandContext context, String source, String sourceId) {

        List<Recipient> recipients = resolve(context, source, sourceId);
        if (recipients.isEmpty()) {
            throw new CommandException("Nobody to list.");
        }
        String list = batches.copyList(context.guildId(), recipients);

        if (list.length() > INLINE_COPY_LIMIT) {
            event.getHook()
                    .sendMessage("Amounts to send — too long to paste inline, so here it is as a file:")
                    .setEphemeral(true)
                    .setFiles(FileUpload.fromData(list.getBytes(StandardCharsets.UTF_8), "cashout.txt"))
                    .queue();
            return;
        }

        // A fenced code block gets Discord's own copy button, and keeps the tab-separated
        // columns intact for pasting elsewhere.
        event.getHook()
                .sendMessage("Send these in game (character name, then silver):\n```\n" + list + "\n```")
                .setEphemeral(true)
                .queue();
    }

    private List<Recipient> resolve(CommandContext context, String source, String sourceId) {
        switch (source) {
            case BatchConfirmationService.SOURCE_ROLE -> {
                Role role = context.guild().getRoleById(sourceId);
                if (role == null) {
                    throw new CommandException("That role no longer exists.");
                }
                return batches.resolveRole(context.guild(), role);
            }
            case BatchConfirmationService.SOURCE_USER -> {
                Member member = context.guild().getMemberById(sourceId);
                if (member == null) {
                    throw new CommandException("That member has left the server.");
                }
                return batches.resolveMember(context.guildId(), member);
            }
            default -> {
                return batches.resolveBattle(context.guildId(), Long.parseLong(sourceId));
            }
        }
    }

    private String sourceLabel(CommandContext context, String source, String sourceId) {
        switch (source) {
            case BatchConfirmationService.SOURCE_ROLE -> {
                Role role = context.guild().getRoleById(sourceId);
                return role != null ? "@" + role.getName() : "role " + sourceId;
            }
            case BatchConfirmationService.SOURCE_USER -> {
                return "<@" + sourceId + ">";
            }
            default -> {
                Battle battle = batches.requireBattle(Long.parseLong(sourceId));
                return "CTA " + battle.getAlbionBattleId();
            }
        }
    }
}
