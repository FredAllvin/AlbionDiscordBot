package personal.albiondiscordbot.service;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Battle;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.domain.SplitBattleGroup;
import personal.albiondiscordbot.repository.BattleParticipationRepository;
import personal.albiondiscordbot.repository.BattleRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.repository.SplitBattleGroupRepository;
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
    public static final String SOURCE_USER = "u";

    /**
     * One battle, its id carried directly in the custom id. No longer minted — one CTA is
     * often several battles — but still resolved, so a preview created before
     * {@link #SOURCE_BATTLES} existed keeps working.
     */
    public static final String SOURCE_BATTLE = "b";

    /** Several battles, looked up by key from {@code split_battle_group}. */
    public static final String SOURCE_BATTLES = "bs";

    /**
     * Rows shown inline before the list is truncated in favour of the copy button. Only
     * ever the second of the two limits in {@link #fencedTable} to bite; the character
     * budget is the one that has to hold.
     */
    private static final int PREVIEW_ROWS = 25;

    private final BalanceService balances;
    private final RegistrationRepository registrations;
    private final BattleRepository battles;
    private final BattleParticipationRepository participations;
    private final SplitBattleGroupRepository battleGroups;

    public BatchConfirmationService(
            BalanceService balances,
            RegistrationRepository registrations,
            BattleRepository battles,
            BattleParticipationRepository participations,
            SplitBattleGroupRepository battleGroups) {
        this.balances = balances;
        this.registrations = registrations;
        this.battles = battles;
        this.participations = participations;
        this.battleGroups = battleGroups;
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

    /**
     * Everyone registered here who fought in any of these battles — each person once,
     * however many of the fights they were in.
     *
     * <p>That "once" is the whole point of taking a list. A CTA that broke into three
     * killboards used to need three splits, which paid the people who stayed for all of
     * it three times and the person who came for one fight once. The union pays turning
     * up, which is what the silver is for.
     */
    public List<Recipient> resolveBattles(long discordGuildId, List<Long> albionBattleIds) {
        return registrations.findParticipantsOfBattles(discordGuildId, albionBattleIds).stream()
                .map(r -> new Recipient(r.getDiscordUserId(), r.getAlbionPlayerName(), true))
                .toList();
    }

    public Battle requireBattle(long albionBattleId) {
        return battles.findByAlbionBattleId(albionBattleId)
                .orElseThrow(() -> new CommandException(
                        "Battle `%d` is not tracked.".formatted(albionBattleId)));
    }

    /** Distinct guild members across these battles, registered or not. */
    public long countOurFighters(List<Long> albionBattleIds) {
        return participations.countFightersIn(albionBattleIds);
    }

    // ------------------------------------------------------------ battle groups

    /**
     * Records which battles a preview covers and returns the key its buttons will carry.
     *
     * <p>Every other piece of a pending batch lives in the Discord custom id, which caps
     * at 100 characters — enough for three or four battle ids and no more. Rather than cap
     * a CTA at four killboards, which would push an officer into running two splits and
     * double-paying the overlap, the list goes to the database and the button carries a
     * key. Same durability: a preview still survives a restart with nothing to expire.
     */
    @Transactional
    public String rememberBattles(long discordGuildId, List<Long> albionBattleIds) {
        String key = newGroupKey();
        battleGroups.save(new SplitBattleGroup(key, discordGuildId, albionBattleIds));
        return key;
    }

    /** The battles behind a key minted by {@link #rememberBattles}. */
    public List<Long> battlesOf(long discordGuildId, String groupKey) {
        return battleGroups
                .findByGroupKeyAndDiscordGuildId(groupKey, discordGuildId)
                .orElseThrow(() -> new CommandException(
                        "That confirmation no longer knows which fights it covers. Run `/split-cta` "
                                + "again — nothing has been credited."))
                .ids();
    }

    /**
     * How a split names the fights it paid, in the preview, the public announcement and
     * the ledger note behind every member's {@code /balance history}.
     */
    public String battleLabel(List<Long> albionBattleIds) {
        if (albionBattleIds.size() == 1) {
            return "CTA " + albionBattleIds.get(0);
        }
        return "CTA across %d fights (%s)"
                .formatted(
                        albionBattleIds.size(),
                        albionBattleIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
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

        embed.addField(
                "Balances after this split",
                fencedTable(recipients.size(), i -> {
                    Recipient recipient = recipients.get(i);
                    long current = balances.balanceOf(discordGuildId, recipient.discordUserId());
                    return "%-20s %15s -> %15s\n"
                            .formatted(
                                    trim(recipient.label(), 20),
                                    Formatting.silver(current),
                                    Formatting.silver(current + amountEach));
                }),
                false);

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

        embed.addField(
                "Send these amounts in game",
                fencedTable(owed.size(), i -> {
                    Recipient recipient = owed.get(i);
                    return "%-20s %15s\n"
                            .formatted(
                                    trim(recipient.label(), 20),
                                    Formatting.silver(
                                            balances.balanceOf(discordGuildId, recipient.discordUserId())));
                }),
                false);

        addUnregisteredNote(owed, embed);
        embed.setFooter("Confirm once you have sent the silver, or Deny to cancel.");
        return embed.build();
    }

    /**
     * A fenced table of at most {@code rows} rows that always fits inside an embed field.
     *
     * <p>The budget that matters is <strong>characters</strong>, not rows. Discord caps a
     * field value at {@value MessageEmbed#VALUE_MAX_LENGTH} and JDA refuses to build a
     * longer one, so counting only rows was not a cap at all: a split row is 56 characters
     * wide and {@link #PREVIEW_ROWS} of them come to 1,407.
     * Every CTA-sized {@code /split-cta} therefore died while building its own preview —
     * the roster is dozens of people, the field went over on the 19th, and the officer got
     * "Something went wrong running that command" with no silver credited and nothing to
     * act on. The cashout table is two columns and came to 957, which is the only reason
     * the identical row cap was survivable there.
     *
     * <p>Rows end in {@code \n} rather than {@code %n}: this is a Discord message, not
     * console output, and on the Windows box this is developed on {@code %n} is two
     * characters. That alone moved where the field overflowed.
     *
     * <p>The tail is reserved for before the rows are laid down, at its widest — it can
     * only get shorter as more rows fit — so a table can never be one row away from
     * overflowing on the sentence that admits it truncated.
     */
    private static String fencedTable(int rows, IntFunction<String> rowAt) {
        String open = "```\n";
        String close = "```";
        int budget = MessageEmbed.VALUE_MAX_LENGTH - open.length() - close.length();
        int tailReserve = tail(rows).length();

        StringBuilder body = new StringBuilder();
        int shown = 0;
        int limit = Math.min(rows, PREVIEW_ROWS);
        for (int i = 0; i < limit; i++) {
            String row = rowAt.apply(i);
            // The last row of the whole list owes no room to a tail that will not be
            // written, so a list that exactly fits is not truncated for the sake of
            // saying it was.
            int reserve = i == rows - 1 ? 0 : tailReserve;
            if (body.length() + row.length() + reserve > budget) {
                break;
            }
            body.append(row);
            shown++;
        }
        return open + body + (shown < rows ? tail(rows - shown) : "") + close;
    }

    /** How a truncated table admits what it left out. Everyone omitted is still paid. */
    private static String tail(int omitted) {
        return "... and %d more\n".formatted(omitted);
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
     *
     * <p>The trailing segment is a token minted per preview. It is what the database
     * claims when the batch is applied, which is what stops a double-click crediting the
     * whole role twice. Deliberately <em>not</em> derived from the amount and recipients:
     * running the same split twice on purpose has to keep working, so it is only the same
     * preview that cannot be confirmed twice.
     */
    public List<Button> buttons(
            String op, String source, String sourceId, long amountEach, long invokerId) {

        String suffix = "%s:%s:%s:%d:%d:%s"
                .formatted(op, source, sourceId, amountEach, invokerId, newClaimToken());
        boolean cashout = OP_CASHOUT.equals(op);
        return List.of(
                cashout
                        ? Button.success(BUTTON_PREFIX + ":ok:" + suffix, "Sent — clear balances")
                        : Button.success(BUTTON_PREFIX + ":ok:" + suffix, "Confirm"),
                Button.danger(BUTTON_PREFIX + ":no:" + suffix, "Deny"),
                Button.secondary(BUTTON_PREFIX + ":cp:" + suffix, "📋 Copy list"));
    }

    /**
     * Short on purpose: Discord caps custom ids at 100 characters and the rest of the id
     * already spends most of that on two snowflake ids. 64 bits of randomness in 13
     * base-36 characters is far beyond what a collision would need, and a collision only
     * costs a refused click.
     */
    private static String newClaimToken() {
        return shortToken();
    }

    /**
     * Keys a {@code split_battle_group}, from the same 64 bits and the same 13 characters.
     *
     * <p>Worth being explicit that a collision is not free here the way it is for a claim
     * token: the second write would overwrite the first group's row, and an older preview
     * left open would then resolve to the newer one's battles. It takes on the order of
     * four billion previews to reach an even chance of that, against the handful a guild
     * runs in a week, so the risk is theoretical — but it is a wrong payout rather than a
     * refused click, which is why it is written down.
     */
    private static String newGroupKey() {
        return shortToken();
    }

    private static String shortToken() {
        return Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36);
    }

    /**
     * The copy button on its own, left behind after a confirmed <strong>split</strong>:
     * the balances it lists still exist, so it still answers "who is owed what now".
     *
     * <p>Not used after a cashout — that zeroes every balance in the batch, so the list
     * would come back empty.
     */
    public Button copyButtonOnly(String op, String source, String sourceId, long amountEach, long invokerId) {
        // Its own fresh token. Copy moves no silver so it never claims one, but the id
        // shape has to stay identical or the handler cannot parse it.
        return Button.secondary(
                BUTTON_PREFIX + ":cp:%s:%s:%s:%d:%d:%s"
                        .formatted(op, source, sourceId, amountEach, invokerId, newClaimToken()),
                "📋 Copy list");
    }

    // ---------------------------------------------------------------- executing

    public BalanceService.SplitResult executeSplit(
            long discordGuildId,
            List<Recipient> recipients,
            long amountEach,
            long actorId,
            String sourceLabel,
            String claimToken) {

        return balances.creditSplit(
                discordGuildId, ids(recipients), amountEach, actorId, sourceLabel, claimToken);
    }

    public BalanceService.CashoutResult executeCashout(
            long discordGuildId,
            List<Recipient> recipients,
            long actorId,
            String sourceLabel,
            String claimToken) {

        return balances.cashOut(discordGuildId, ids(recipients), actorId, sourceLabel, claimToken);
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
            out.append(fenceSafe(recipient.label())).append('\t').append(balance).append('\n');
        }
        if (total == 0) {
            return "Nobody is owed anything.";
        }
        out.append("\nTotal to send: ").append(Formatting.silver(total));
        return out.toString();
    }

    /**
     * These tables sit inside a code fence, so the escaping that suits an embed field is
     * the wrong tool — backticks are what break out of a fence, and a Discord display name
     * may contain them. Stripped rather than escaped: a backslash would show up literally
     * in a block an officer reads amounts off.
     */
    private String trim(String value, int max) {
        String safe = fenceSafe(value);
        return safe.length() <= max ? safe : safe.substring(0, max - 1) + "…";
    }

    private static String fenceSafe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("`", "").replace("\n", " ").replace("\r", " ");
    }
}
