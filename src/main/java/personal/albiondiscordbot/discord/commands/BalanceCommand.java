package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.AuditLogService;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.PermissionService;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Balance;
import personal.albiondiscordbot.domain.BalanceTransaction;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.report.BalanceHtmlReport;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.service.BalanceService;
import personal.albiondiscordbot.util.Formatting;
import personal.albiondiscordbot.util.SilverAmountParser;

/** {@code /balance check|add|remove|reset|give|stats} */
@Component
public class BalanceCommand implements SlashCommand {

    /** Enough history to settle an argument without flooding the channel. */
    private static final int HISTORY_LIMIT = 15;

    private final BalanceService balances;
    private final PermissionService permissions;
    private final RegistrationRepository registrations;
    private final BalanceHtmlReport report;
    private final AuditLogService auditLog;
    private final BalanceTransactionRepository ledger;

    private final Map<String, BiConsumer<SlashCommandInteractionEvent, CommandContext>> subcommands;

    public BalanceCommand(
            BalanceService balances,
            PermissionService permissions,
            RegistrationRepository registrations,
            BalanceHtmlReport report,
            AuditLogService auditLog,
            BalanceTransactionRepository ledger) {
        this.balances = balances;
        this.permissions = permissions;
        this.registrations = registrations;
        this.report = report;
        this.auditLog = auditLog;
        this.ledger = ledger;
        this.subcommands = Map.of(
                "check", this::check,
                "add", this::add,
                "remove", this::remove,
                "reset", this::reset,
                "give", this::give,
                "stats", this::stats,
                "history", this::history);
    }

    @Override
    public String name() {
        return "balance";
    }

    @Override
    public SlashCommandData definition() {
        OptionData targetUser = new OptionData(OptionType.USER, "user", "The member", true);
        OptionData amount = new OptionData(
                OptionType.STRING, "amount", "Amount, e.g. 1m, 1.5m, 500k or 1000000", true);
        OptionData reason = new OptionData(OptionType.STRING, "reason", "Why", false);

        return Commands.slash("balance", "View and manage silver balances")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(
                        new SubcommandData("check", "Show a balance")
                                .addOptions(new OptionData(
                                        OptionType.USER, "user", "Whose balance (default yourself)", false)),
                        new SubcommandData("add", "Give silver to a member (staff)")
                                .addOptions(targetUser, amount, reason),
                        new SubcommandData("remove", "Take silver from a member (staff)")
                                .addOptions(targetUser, amount, reason),
                        new SubcommandData("reset", "Set a member's balance to zero (staff)")
                                .addOptions(targetUser),
                        new SubcommandData("give", "Transfer silver from your own balance")
                                .addOptions(targetUser, amount),
                        new SubcommandData("history", "Show recent balance changes and who made them")
                                .addOptions(new OptionData(
                                        OptionType.USER, "user", "Whose history (default yourself)", false)),
                        new SubcommandData("stats", "Export every balance as an HTML file (staff)")
                                .addOptions(new OptionData(
                                        OptionType.BOOLEAN,
                                        "public",
                                        "Post it visibly in the channel instead of only to you",
                                        false)));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        String sub = event.getSubcommandName();
        BiConsumer<SlashCommandInteractionEvent, CommandContext> handler = subcommands.get(sub);
        if (handler == null) {
            throw new CommandException("Unknown subcommand `%s`.".formatted(sub));
        }
        handler.accept(event, context);
    }

    private void check(SlashCommandInteractionEvent event, CommandContext context) {
        Member target = event.getOption("user", OptionMapping::getAsMember);

        // Anyone may check themselves; only staff may check other people.
        if (target != null && target.getIdLong() != context.callerId()) {
            permissions.requireStaff(context.member(), context.config());
        }
        Member subject = target != null ? target : context.member();

        long amount = balances.balanceOf(context.guildId(), subject.getIdLong());
        String ign = registrations
                .findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(context.guildId(), subject.getIdLong())
                .map(Registration::getAlbionPlayerName)
                .orElse(null);

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(0x3498DB))
                .setAuthor(subject.getEffectiveName(), null, subject.getEffectiveAvatarUrl())
                .addField("Balance", Formatting.silver(amount) + " silver", false);
        if (ign != null) {
            embed.addField("In-game name", Formatting.escapeMarkdown(ign), false);
        }
        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private void add(SlashCommandInteractionEvent event, CommandContext context) {
        permissions.requireStaff(context.member(), context.config());
        User target = requiredUser(event);
        long amount = SilverAmountParser.parse(event.getOption("amount", OptionMapping::getAsString));
        String reason = event.getOption("reason", OptionMapping::getAsString);

        long after = balances.add(context.guildId(), target.getIdLong(), amount, context.callerId(), reason);
        auditLog.money(context, "Added **%s** to %s (now %s)%s".formatted(
                Formatting.silver(amount), target.getAsMention(), Formatting.silver(after),
                reason == null ? "" : " — " + reason));

        event.getHook()
                .sendMessage("Added **%s** to %s. New balance: **%s**."
                        .formatted(Formatting.silver(amount), target.getAsMention(), Formatting.silver(after)))
                .queue();
    }

    private void remove(SlashCommandInteractionEvent event, CommandContext context) {
        permissions.requireStaff(context.member(), context.config());
        User target = requiredUser(event);
        long amount = SilverAmountParser.parse(event.getOption("amount", OptionMapping::getAsString));
        String reason = event.getOption("reason", OptionMapping::getAsString);

        long after = balances.remove(context.guildId(), target.getIdLong(), amount, context.callerId(), reason);
        auditLog.money(context, "Removed **%s** from %s (now %s)%s".formatted(
                Formatting.silver(amount), target.getAsMention(), Formatting.silver(after),
                reason == null ? "" : " — " + reason));

        event.getHook()
                .sendMessage("Removed **%s** from %s. New balance: **%s**."
                        .formatted(Formatting.silver(amount), target.getAsMention(), Formatting.silver(after)))
                .queue();
    }

    private void reset(SlashCommandInteractionEvent event, CommandContext context) {
        permissions.requireStaff(context.member(), context.config());
        User target = requiredUser(event);

        long previous = balances.reset(context.guildId(), target.getIdLong(), context.callerId());
        auditLog.money(context, "Reset %s to 0 (was **%s**)".formatted(
                target.getAsMention(), Formatting.silver(previous)));

        event.getHook()
                .sendMessage("Reset %s to **0**. They previously held **%s**."
                        .formatted(target.getAsMention(), Formatting.silver(previous)))
                .queue();
    }

    private void give(SlashCommandInteractionEvent event, CommandContext context) {
        User target = requiredUser(event);
        long amount = SilverAmountParser.parse(event.getOption("amount", OptionMapping::getAsString));

        if (target.isBot()) {
            throw new CommandException("You cannot give silver to a bot.");
        }
        balances.give(context.guildId(), context.callerId(), target.getIdLong(), amount);

        event.getHook()
                .sendMessage("Sent **%s** to %s. You now have **%s**."
                        .formatted(
                                Formatting.silver(amount),
                                target.getAsMention(),
                                Formatting.silver(balances.balanceOf(context.guildId(), context.callerId()))))
                .queue();
    }

    private void stats(SlashCommandInteractionEvent event, CommandContext context) {
        permissions.requireStaff(context.member(), context.config());
        boolean isPublic = event.getOption("public", false, OptionMapping::getAsBoolean);

        List<Balance> all = balances.allBalances(context.guildId());
        if (all.isEmpty()) {
            throw new CommandException("Nobody has a balance yet.");
        }

        List<BalanceHtmlReport.Row> rows = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            Balance balance = all.get(i);
            long userId = balance.getDiscordUserId();

            Member member = context.guild().getMemberById(userId);
            String displayName = member != null ? member.getEffectiveName() : "Unknown member (" + userId + ")";
            String ign = registrations
                    .findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(context.guildId(), userId)
                    .map(Registration::getAlbionPlayerName)
                    .orElse(null);

            rows.add(new BalanceHtmlReport.Row(i + 1, displayName, ign, balance.getAmount()));
        }

        long total = balances.totalSilver(context.guildId());
        String html = report.render(context.guild().getName(), rows, total);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String filename = "balances-%s-%s.html".formatted(context.guildId(), LocalDate.now());

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Balance report")
                .setColor(new Color(0x9B59B6))
                .addField("Members", Formatting.silver(rows.size()), true)
                .addField("Total silver", Formatting.silver(total), true)
                .setDescription("Open the attached file for the full list.");

        event.getHook()
                .sendMessageEmbeds(embed.build())
                .setFiles(FileUpload.fromData(bytes, filename))
                .setEphemeral(!isPublic)
                .queue();
    }

    /**
     * Reads back the append-only ledger. This is what settles "I never got my split" —
     * every entry records who changed the balance, by how much, and why.
     */
    private void history(SlashCommandInteractionEvent event, CommandContext context) {
        Member target = event.getOption("user", OptionMapping::getAsMember);

        // Anyone may read their own history; other people's is staff-only.
        if (target != null && target.getIdLong() != context.callerId()) {
            permissions.requireStaff(context.member(), context.config());
        }
        Member subject = target != null ? target : context.member();

        List<BalanceTransaction> entries = ledger.findByDiscordGuildIdAndDiscordUserIdOrderByCreatedAtDesc(
                context.guildId(), subject.getIdLong(), PageRequest.of(0, HISTORY_LIMIT));

        if (entries.isEmpty()) {
            throw new CommandException(
                    (target == null ? "You have" : subject.getAsMention() + " has") + " no balance history yet.");
        }

        StringBuilder body = new StringBuilder();
        for (BalanceTransaction entry : entries) {
            // <t:epoch:R> renders as "3 hours ago" in each viewer's own timezone.
            body.append("<t:")
                    .append(entry.getCreatedAt().getEpochSecond())
                    .append(":R> ")
                    .append(entry.getDelta() >= 0 ? "+" : "-")
                    .append("**")
                    .append(Formatting.silver(Math.abs(entry.getDelta())))
                    .append("** `")
                    .append(entry.getType())
                    .append("` → ")
                    .append(Formatting.silver(entry.getBalanceAfter()));

            if (entry.getActorDiscordUserId() != null
                    && !entry.getActorDiscordUserId().equals(subject.getIdLong())) {
                body.append(" by <@").append(entry.getActorDiscordUserId()).append(">");
            }
            if (entry.getNote() != null && !entry.getNote().isBlank()) {
                body.append(" — ").append(Formatting.escapeMarkdown(entry.getNote()));
            }
            body.append("\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(0x3498DB))
                .setAuthor(subject.getEffectiveName(), null, subject.getEffectiveAvatarUrl())
                .setTitle("Balance history")
                // Embed descriptions cap at 4096 characters.
                .setDescription(body.length() <= 4000 ? body.toString() : body.substring(0, 4000) + "…")
                .addField(
                        "Current balance",
                        Formatting.silver(balances.balanceOf(context.guildId(), subject.getIdLong())),
                        true)
                .setFooter("Showing the last " + entries.size() + " changes");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private User requiredUser(SlashCommandInteractionEvent event) {
        return Optional.ofNullable(event.getOption("user", OptionMapping::getAsUser))
                .orElseThrow(() -> new CommandException("You need to pick a member."));
    }
}
