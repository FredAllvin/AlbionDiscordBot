package personal.albiondiscordbot.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Balance;
import personal.albiondiscordbot.domain.BalanceTransaction;
import personal.albiondiscordbot.domain.TransactionType;
import personal.albiondiscordbot.repository.BalanceDao;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.BatchClaimDao;
import personal.albiondiscordbot.util.Formatting;

/**
 * Silver ledger.
 *
 * <p>Every mutation writes a {@link BalanceTransaction} in the same transaction as the
 * balance change, so the ledger can never drift from the balances it explains.
 */
@Service
public class BalanceService {

    private final BalanceDao dao;
    private final BalanceRepository balances;
    private final BalanceTransactionRepository ledger;
    private final BatchClaimDao claims;

    public BalanceService(
            BalanceDao dao,
            BalanceRepository balances,
            BalanceTransactionRepository ledger,
            BatchClaimDao claims) {
        this.dao = dao;
        this.balances = balances;
        this.ledger = ledger;
        this.claims = claims;
    }

    /** Refused a second time, so one confirmation cannot move silver twice. */
    private void claimOrThrow(String claimToken, long guildId, String batchId) {
        if (!claims.claim(claimToken, guildId, batchId)) {
            throw new CommandException(
                    "That confirmation has already been used. Nothing was moved a second time — "
                            + "run the command again if you meant to repeat it.");
        }
    }

    @Transactional(readOnly = true)
    public long balanceOf(long guildId, long userId) {
        return dao.currentAmount(guildId, userId);
    }

    @Transactional(readOnly = true)
    public List<Balance> allBalances(long guildId) {
        return balances.findByDiscordGuildIdOrderByAmountDesc(guildId);
    }

    @Transactional(readOnly = true)
    public long totalSilver(long guildId) {
        return balances.sumAmountByDiscordGuildId(guildId);
    }

    @Transactional
    public long add(long guildId, long userId, long amount, long actorId, String note) {
        long after = dao.applyDelta(guildId, userId, amount);
        ledger.save(new BalanceTransaction(guildId, userId, actorId, TransactionType.ADD, amount, after)
                .withNote(note));
        return after;
    }

    @Transactional
    public long remove(long guildId, long userId, long amount, long actorId, String note) {
        OptionalLong after = dao.debitIfSufficient(guildId, userId, amount);
        if (after.isEmpty()) {
            throw new CommandException(
                    "That would overdraw the balance. They hold %s and you tried to remove %s."
                            .formatted(
                                    Formatting.silver(dao.currentAmount(guildId, userId)),
                                    Formatting.silver(amount)));
        }
        ledger.save(new BalanceTransaction(
                        guildId, userId, actorId, TransactionType.REMOVE, -amount, after.getAsLong())
                .withNote(note));
        return after.getAsLong();
    }

    @Transactional
    public long reset(long guildId, long userId, long actorId) {
        long previous = dao.resetToZero(guildId, userId);
        ledger.save(new BalanceTransaction(guildId, userId, actorId, TransactionType.RESET, -previous, 0L)
                .withNote("Reset from " + Formatting.silver(previous)));
        return previous;
    }

    /**
     * Transfers silver between two users.
     *
     * <p>Both legs share one transaction, so money is never half-moved.
     *
     * <p>The two rows are always touched in <strong>ascending user id order</strong>,
     * never sender-then-recipient. Touching them in transfer order deadlocks as soon as
     * one transfer runs A→B while another runs B→A, because each transaction holds the
     * row the other is waiting for. Ordering by id gives every transaction the same
     * lock sequence, so they queue instead of deadlocking.
     *
     * <p>That means the credit sometimes happens before the funds check. It is safe:
     * an overdraft throws, and the transaction rolls the credit back.
     */
    @Transactional
    public void give(long guildId, long fromUserId, long toUserId, long amount) {
        if (fromUserId == toUserId) {
            throw new CommandException("You cannot give silver to yourself.");
        }

        long senderAfter;
        long recipientAfter;
        if (fromUserId < toUserId) {
            senderAfter = debitOrThrow(guildId, fromUserId, amount);
            recipientAfter = dao.applyDelta(guildId, toUserId, amount);
        } else {
            recipientAfter = dao.applyDelta(guildId, toUserId, amount);
            senderAfter = debitOrThrow(guildId, fromUserId, amount);
        }

        ledger.save(new BalanceTransaction(
                        guildId, fromUserId, fromUserId, TransactionType.GIVE_OUT, -amount, senderAfter)
                .withCounterparty(toUserId));
        ledger.save(new BalanceTransaction(
                        guildId, toUserId, fromUserId, TransactionType.GIVE_IN, amount, recipientAfter)
                .withCounterparty(fromUserId));
    }

    private long debitOrThrow(long guildId, long userId, long amount) {
        OptionalLong after = dao.debitIfSufficient(guildId, userId, amount);
        if (after.isEmpty()) {
            throw new CommandException(
                    "You only have %s, so you cannot give %s."
                            .formatted(
                                    Formatting.silver(dao.currentAmount(guildId, userId)),
                                    Formatting.silver(amount)));
        }
        return after.getAsLong();
    }

    /**
     * Credits a flat {@code amountEach} to every user — a share of loot going <em>into</em>
     * the ledger. This is per person, not a total to divide, so 15 members at 1,000,000
     * adds 15,000,000 to what the guild owes.
     *
     * @param claimToken identifies the confirmation being acted on, so the same one cannot
     *     credit twice. Minted per preview rather than derived from the amount and
     *     recipients, so deliberately repeating a split is still allowed.
     * @return each recipient's resulting balance
     */
    @Transactional
    public SplitResult creditSplit(
            long guildId,
            Set<Long> userIds,
            long amountEach,
            long actorId,
            String sourceLabel,
            String claimToken) {
        // A Set already, but copy defensively: duplicates here would silently pay someone twice.
        Set<Long> recipients = new LinkedHashSet<>(userIds);
        if (recipients.isEmpty()) {
            throw new CommandException("There is nobody to credit.");
        }

        String batchId = UUID.randomUUID().toString();
        // Before any money moves: a refused claim must leave the balances untouched.
        claimOrThrow(claimToken, guildId, batchId);

        Map<Long, Long> after = dao.creditEach(guildId, recipients, amountEach);

        after.forEach((userId, balanceAfter) -> ledger.save(
                new BalanceTransaction(guildId, userId, actorId, TransactionType.SPLIT, amountEach, balanceAfter)
                        .withReference(batchId)
                        .withNote("Split from " + sourceLabel)));

        return new SplitResult(batchId, recipients.size(), amountEach, amountEach * recipients.size());
    }

    /**
     * Clears balances because the members have been sent their silver in game.
     *
     * <p>The bot cannot see an in-game trade, so this records that an officer says the
     * transfer happened. Each member is zeroed and the amount they were owed is written
     * to the ledger, which is what makes a cashout auditable and reversible.
     *
     * <p>Anyone at zero or below is skipped rather than touched: there is nothing to hand
     * over, and a negative balance is a debt that a cashout must not quietly erase.
     */
    @Transactional
    public CashoutResult cashOut(
            long guildId, Set<Long> userIds, long actorId, String sourceLabel, String claimToken) {
        Set<Long> candidates = new LinkedHashSet<>(userIds);
        if (candidates.isEmpty()) {
            throw new CommandException("There is nobody to cash out.");
        }

        String batchId = UUID.randomUUID().toString();
        // A second confirmation would find the balances already zeroed and report that
        // nobody is owed anything, which reads like a bug rather than a duplicate click.
        claimOrThrow(claimToken, guildId, batchId);

        Map<Long, Long> paid = new java.util.LinkedHashMap<>();
        List<Long> skipped = new ArrayList<>();
        long total = 0;

        for (Long userId : candidates) {
            // resetToZero takes a row lock and returns what was there, so a concurrent
            // credit either lands before the cashout or after it, never inside it.
            long owed = dao.resetToZero(guildId, userId);
            if (owed <= 0) {
                skipped.add(userId);
                continue;
            }
            paid.put(userId, owed);
            total += owed;

            ledger.save(new BalanceTransaction(
                            guildId, userId, actorId, TransactionType.CASHOUT, -owed, 0L)
                    .withReference(batchId)
                    .withNote("Cashed out " + Formatting.silver(owed) + " — " + sourceLabel));
        }

        if (paid.isEmpty()) {
            throw new CommandException("Nobody in that group is owed any silver.");
        }
        return new CashoutResult(batchId, paid, total, skipped);
    }

    /**
     * Reverses a whole batch — a split that was wrong, or a cashout that never actually
     * happened. Each entry is negated, so reversing a split takes silver back and
     * reversing a cashout restores what was owed.
     *
     * <p>Balances are allowed to go <strong>negative</strong> here, unlike every other
     * debit. If someone has already spent an erroneous split, the alternatives are to
     * refuse the reversal (leaving silver that was never earned) or to claw back only
     * part of it (creating silver from nothing and breaking the invariant that the
     * ledger explains every balance). A negative balance is the honest representation:
     * they owe it back.
     */
    @Transactional
    public UndoResult undoBatch(long guildId, String batchId, long actorId) {
        // Lock the batch's rows before reading them. The "already reversed" check below is
        // a read followed by a write, and under READ COMMITTED a second /undo running at
        // the same moment cannot see the first one's uncommitted reversals — so without
        // this every concurrent caller passed the check and applied a full reversal.
        // Measured before the lock: eight simultaneous calls, eight reversals, none
        // refused, a member taken from 1,000,000 to -7,000,000.
        //
        // Taking the lock first makes the second caller wait, and its check then runs in a
        // new statement snapshot that does see the committed reversals. ux_reversal_once
        // enforces the same rule in the database, so this can only ever fail loudly.
        dao.lockBatch(batchId);

        List<BalanceTransaction> batch = ledger.findByReference(batchId).stream()
                .filter(t -> t.getDiscordGuildId() == guildId)
                .toList();

        if (batch.isEmpty()) {
            throw new CommandException(
                    "No batch found with id `%s` on this server. It is shown in the footer of the message."
                            .formatted(batchId));
        }
        if (batch.stream().anyMatch(t -> t.getType() == TransactionType.REVERSAL)) {
            throw new CommandException("That batch has already been reversed.");
        }

        List<BalanceTransaction> entries = batch.stream()
                .filter(t -> t.getType() == TransactionType.SPLIT || t.getType() == TransactionType.CASHOUT)
                .toList();
        if (entries.isEmpty()) {
            throw new CommandException("Batch `%s` contains nothing to reverse.".formatted(batchId));
        }

        boolean wasCashout = entries.get(0).getType() == TransactionType.CASHOUT;
        List<Long> wentNegative = new ArrayList<>();
        long moved = 0;

        for (BalanceTransaction entry : entries) {
            // Negating the original delta reverses either direction correctly: a split
            // was positive so this debits, a cashout was negative so this credits back.
            long reversal = -entry.getDelta();
            long after = dao.applyDelta(guildId, entry.getDiscordUserId(), reversal);
            moved += Math.abs(reversal);

            if (after < 0) {
                wentNegative.add(entry.getDiscordUserId());
            }
            ledger.save(new BalanceTransaction(
                            guildId,
                            entry.getDiscordUserId(),
                            actorId,
                            TransactionType.REVERSAL,
                            reversal,
                            after)
                    .withReference(batchId)
                    .withNote("Reversal of " + (wasCashout ? "cashout " : "split ") + batchId));
        }

        return new UndoResult(batchId, entries.size(), moved, wentNegative, wasCashout);
    }

    public record SplitResult(String batchId, int recipientCount, long amountEach, long totalCredited) {
    }

    /**
     * @param paid how much each member was owed and has now been handed in game
     * @param skipped members who were owed nothing, so nothing was cleared
     */
    public record CashoutResult(
            String batchId, Map<Long, Long> paid, long totalPaid, List<Long> skipped) {

        public int paidCount() {
            return paid.size();
        }
    }

    /**
     * @param wentNegative users whose balance is now below zero because they had already
     *     spent some of the reversed silver
     * @param wasCashout true when the reversed batch was a cashout, so balances went back up
     */
    public record UndoResult(
            String batchId,
            int reversedCount,
            long totalMoved,
            List<Long> wentNegative,
            boolean wasCashout) {
    }
}
