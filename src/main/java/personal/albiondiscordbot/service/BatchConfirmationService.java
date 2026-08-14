package personal.albiondiscordbot.service;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Battle;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.repository.BattleParticipationRepository;
import personal.albiondiscordbot.repository.BattleRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.util.Formatting;

/**
 * The confirm-before-moving-silver flow, shared by the two batch operations that run in
 * opposite directions:
 *
 * <ul>
 *   <li><strong>Split</strong> — credits a share of loot, so the guild now owes it.
 *   <li><strong>Cashout</strong> — clears balances because the silver has been handed
 *       over in game.
 * </ul>
 *
 * <p>Both get a preview because both are annoying to reverse, and both offer the copy
 * list — which matters most for a cashout, since that is the list an officer types from
 * while actually sending the silver.
 */
@Service
public class BatchConfirmationService {

    /** Discord custom ids cap at 100 characters; everything is encoded within that. */
    public static final String BUTTON_PREFIX = "bt";

    public static final String OP_SPLIT = "split";
    public static final String OP_CASHOUT = "cash";

    public static final String SOURCE_ROLE = "r";
    public static final String SOURCE_BATTLE = "b";
    public static final String SOURCE_USER = "u";

    /** Rows shown inline before the list is truncated in favour of the copy button. */
    private static final int PREVIEW_ROWS = 25;

    private final BalanceService balances;
    private final RegistrationRepository registrations;
    private final BattleRepository battles;
    private final BattleParticipationRepository participations;

    public BatchConfirmationService(
            BalanceService balances,
            RegistrationRepository registrations,
            BattleRepository battles,
            BattleParticipationRepository participations) {
        this.balances = balances;
        this.registrations = registrations;
        this.battles = battles;
        this.participations = participations;
    }

    /**
     * @param discordUserId who is affected
     * @param label in-game name where known, otherwise the Discord display name
     * @param registered whether an Albion character is linked
     */
    public record Recipient(long discordUserId, String label, boolean registered) {
    }

    // ---------------------------------------------------------------- resolving

    public List<Recipient> resolveRole(Guild guild, Role role) {
        List<Member> members = guild.getMembersWithRoles(role).stream()
                .filter(m -> !m.getUser().isBot())
                .toList();

        List<Recipient> recipients = new ArrayList<>(members.size());
        for (Member member : members) {
            Registration registration = registrations
                    .findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(guild.getIdLong(), member.getIdLong())
                    .orElse(null);
            recipients.add(new Recipient(
                    member.getIdLong(),
                    registration != null ? registration.getAlbionPlayerName() : member.getEffectiveName(),
                    registration != null));
        }
        return recipients;
    }

    /** A single member — cashing one person out is more common than a whole role. */
    public List<Recipient> resolveMember(long discordGuildId, Member member) {
        Registration registration = registrations
                .findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(discordGuildId, member.getIdLong())
                .orElse(null);

        return List.of(new Recipient(
                member.getIdLong(),
                registration != null ? registration.getAlbionPlayerName() : member.getEffectiveName(),
                registration != null));
    }

    public List<Recipient> resolveBattle(long discordGuildId, long albionBattleId) {
        return registrations.findParticipantsOfBattle(discordGuildId, albionBattleId).stream()
                .map(r -> new Recipient(r.getDiscordUserId(), r.getAlbionPlayerName(), true))
                .toList();
    }

    public Battle requireBattle(long albionBattleId) {
        return battles.findByAlbionBattleId(albionBattleId)
                .orElseThrow(() -> new CommandException(
                        "Battle `%d` is not tracked.".formatted(albionBattleId)));
    }

    public long countOurFighters(long albionBattleId) {
        return participations.countByAlbionBattleId(albionBattleId);
    }

    /** Total the guild owes these members. Negative balances are debts, so they do not offset. */
    public long totalOwed(long discordGuildId, List<Recipient> recipients) {
        return recipients.stream()
                .mapToLong(r -> Math.max(0, balances.balanceOf(discordGuildId, r.discordUserId())))
                .sum();
    }

    // ---------------------------------------------------------------- previews

    /** Preview for a split: what each balance becomes once the share is credited. */
    public MessageEmbed previewSplit(
            long discordGuildId, List<Recipient> recipients, long amountEach, String sourceLabel) {

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Confirm split")
                .setColor(new Color(0xF1C40F))
                .setDescription(("Credit **%s** to **each** of **%d** member%s of %s.\n"
                                + "This adds to what the guild owes them — it does not send any silver in game.\n"
                                + "Nothing has moved yet.")
                        .formatted(
                                Formatting.silver(amountEach),
                                recipients.size(),
                                recipients.size() == 1 ? "" : "s",
                                sourceLabel))
                .addField("Each", Formatting.silver(amountEach), true)
                .addField("Members", Integer.toString(recipients.size()), true)
                .addField("Total credited", Formatting.silver(amountEach * recipients.size()), true);

        StringBuilder table = new StringBuilder("```\n");
        int shown = Math.min(recipients.size(), PREVIEW_ROWS);
        for (int i = 0; i < shown; i++) {
            Recipient recipient = recipients.get(i);
            long current = balances.balanceOf(discordGuildId, recipient.discordUserId());
            table.append(String.format(
                    "%-20s %15s -> %15s%n",
                    trim(recipient.label(), 20),
                    Formatting.silver(current),
                    Formatting.silver(current + amountEach)));
        }
        if (recipients.size() > shown) {
            table.append("... and ").append(recipients.size() - shown).append(" more\n");
        }
        table.append("```");
        embed.addField("Balances after this split", table.toString(), false);

        addUnregisteredNote(recipients, embed);
        embed.setFooter("Confirm to credit the balances, or Deny to cancel.");
        return embed.build();
    }

    /** Preview for a cashout: exactly what has to be sent in game, and to whom. */
    public MessageEmbed previewCashout(
            long discordGuildId, List<Recipient> recipients, String sourceLabel) {

        List<Recipient> owed = new ArrayList<>();
        long total = 0;
        for (Recipient recipient : recipients) {
            long balance = balances.balanceOf(discordGuildId, recipient.discordUserId());
            if (balance > 0) {
                owed.add(recipient);
                total += balance;
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Confirm cashout")
                .setColor(new Color(0xE67E22))
                .setDescription(("About to clear the balances of **%d** member%s of %s, totalling **%s**.\n\n"
                                + "**Send the silver in game first.** Use 📋 Copy list for the amounts, then "
                                + "confirm to zero their balances.")
                        .formatted(
                                owed.size(),
                                owed.size() == 1 ? "" : "s",
                                sourceLabel,
                                Formatting.silver(total)))
                .addField("To pay out", Formatting.silver(total), true)
                .addField("Members owed", Integer.toString(owed.size()), true);

        int skipped = recipients.size() - owed.size();
        if (skipped > 0) {
            embed.addField("Owed nothing", "%d skipped".formatted(skipped), true);
        }

        StringBuilder table = new StringBuilder("```\n");
        int shown = Math.min(owed.size(), PREVIEW_ROWS);
        for (int i = 0; i < shown; i++) {
            Recipient recipient = owed.get(i);
            table.append(String.format(
                    "%-20s %15s%n",
                    trim(recipient.label(), 20),
                    Formatting.silver(balances.balanceOf(discordGuildId, recipient.discordUserId()))));
        }
        if (owed.size() > shown) {
            table.append("... and ").append(owed.size() - shown).append(" more\n");
        }
        table.append("```");
        embed.addField("Send these amounts in game", table.toString(), false);

        addUnregisteredNote(owed, embed);
        embed.setFooter("Confirm once you have sent the silver, or Deny to cancel.");
        return embed.build();
    }

    private void addUnregisteredNote(List<Recipient> recipients, EmbedBuilder embed) {
        long unregistered = recipients.stream().filter(r -> !r.registered()).count();
        if (unregistered > 0) {
            embed.addField(
                    "Heads up",
                    ("%d of them have no in-game name registered, so the list shows their Discord name — "
                                    + "you will have to work out who that is in game.")
                            .formatted(unregistered),
                    false);
        }
    }

    // ---------------------------------------------------------------- buttons

    /**
     * All state is in the ids, so these buttons keep working after a restart and nothing
     * needs to be held in memory or expired.
     */
    public List<Button> buttons(
            String op, String source, String sourceId, long amountEach, long invokerId) {

        String suffix = "%s:%s:%s:%d:%d".formatted(op, source, sourceId, amountEach, invokerId);
        boolean cashout = OP_CASHOUT.equals(op);
        return List.of(
                cashout
                        ? Button.success(BUTTON_PREFIX + ":ok:" + suffix, "Sent — clear balances")
                        : Button.success(BUTTON_PREFIX + ":ok:" + suffix, "Confirm"),
                Button.danger(BUTTON_PREFIX + ":no:" + suffix, "Deny"),
                Button.secondary(BUTTON_PREFIX + ":cp:" + suffix, "📋 Copy list"));
    }

    /** After confirming, only the copy button stays useful. */
    public Button copyButtonOnly(String op, String source, String sourceId, long amountEach, long invokerId) {
        return Button.secondary(
                BUTTON_PREFIX + ":cp:%s:%s:%s:%d:%d".formatted(op, source, sourceId, amountEach, invokerId),
                "📋 Copy list");
    }

    // ---------------------------------------------------------------- executing

    public BalanceService.SplitResult executeSplit(
            long discordGuildId, List<Recipient> recipients, long amountEach, long actorId, String sourceLabel) {

        return balances.creditSplit(discordGuildId, ids(recipients), amountEach, actorId, sourceLabel);
    }

    public BalanceService.CashoutResult executeCashout(
            long discordGuildId, List<Recipient> recipients, long actorId, String sourceLabel) {

        return balances.cashOut(discordGuildId, ids(recipients), actorId, sourceLabel);
    }

    private Set<Long> ids(List<Recipient> recipients) {
        return recipients.stream()
                .map(Recipient::discordUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------------------------------------------------------------- copy list

    /**
     * The list an officer types from when sending silver in game: character name and the
     * full balance owed. Anyone at zero is left out — there is nothing to send them.
     */
    public String copyList(long discordGuildId, List<Recipient> recipients) {
        StringBuilder out = new StringBuilder();
        long total = 0;
        for (Recipient recipient : recipients) {
            long balance = balances.balanceOf(discordGuildId, recipient.discordUserId());
            if (balance <= 0) {
                continue;
            }
            total += balance;
            out.append(recipient.label()).append('\t').append(balance).append('\n');
        }
        if (total == 0) {
            return "Nobody is owed anything.";
        }
        out.append("\nTotal to send: ").append(Formatting.silver(total));
        return out.toString();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
