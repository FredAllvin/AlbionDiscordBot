package personal.albiondiscordbot.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Balance writes, as single atomic SQL statements.
 *
 * <p>Read-modify-write in Java would lose updates when two staff run
 * {@code /balance add} at the same moment. Optimistic locking would work but
 * degenerates into retry storms under {@code /payout} across a large role, and
 * pessimistic locking serialises more than necessary. A single statement lets the
 * database's own row locks do the serialising, with no application retry logic at all.
 */
@Repository
public class BalanceDao {

    private final JdbcClient jdbc;

    public BalanceDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds {@code delta} (which may be negative) and returns the resulting balance.
     * Creates the row on first touch.
     */
    public long applyDelta(long discordGuildId, long discordUserId, long delta) {
        return jdbc.sql(
                        """
                        INSERT INTO balance (discord_guild_id, discord_user_id, amount, updated_at)
                        VALUES (:guildId, :userId, :delta, now())
                        ON CONFLICT (discord_guild_id, discord_user_id)
                        DO UPDATE SET amount = balance.amount + EXCLUDED.amount, updated_at = now()
                        RETURNING amount
                        """)
                .param("guildId", discordGuildId)
                .param("userId", discordUserId)
                .param("delta", delta)
                .query(Long.class)
                .single();
    }

    /**
     * Debits {@code amount} only if the balance covers it, returning the new balance or
     * empty if it does not.
     *
     * <p>The {@code amount >= :amount} guard is inside the statement on purpose: a
     * separate {@code SELECT} to check first would leave a window in which a concurrent
     * withdrawal could drain the balance between the check and the update. A missing
     * row has an implied balance of zero and so correctly fails any positive debit.
     */
    public OptionalLong debitIfSufficient(long discordGuildId, long discordUserId, long amount) {
        List<Long> result = jdbc.sql(
                        """
                        UPDATE balance SET amount = amount - :amount, updated_at = now()
                        WHERE discord_guild_id = :guildId
                          AND discord_user_id = :userId
                          AND amount >= :amount
                        RETURNING amount
                        """)
                .param("guildId", discordGuildId)
                .param("userId", discordUserId)
                .param("amount", amount)
                .query(Long.class)
                .list();

        return result.isEmpty() ? OptionalLong.empty() : OptionalLong.of(result.get(0));
    }

    /**
     * Takes a row lock on every ledger entry in a batch, so two reversals of the same
     * batch queue instead of both passing an "already reversed" check that neither can
     * see the other failing. Returns nothing: the lock, not the rows, is the point.
     */
    public void lockBatch(String reference) {
        jdbc.sql("SELECT id FROM balance_transaction WHERE reference = :reference FOR UPDATE")
                .param("reference", reference)
                .query(Long.class)
                .list();
    }

    /** Sets a balance to zero, returning the amount it held before. */
    public long resetToZero(long discordGuildId, long discordUserId) {
        Optional<Long> previous = jdbc.sql(
                        """
                        SELECT amount FROM balance
                        WHERE discord_guild_id = :guildId AND discord_user_id = :userId
                        FOR UPDATE
                        """)
                .param("guildId", discordGuildId)
                .param("userId", discordUserId)
                .query(Long.class)
                .optional();

        if (previous.isEmpty()) {
            return 0L;
        }
        jdbc.sql(
                        """
                        UPDATE balance SET amount = 0, updated_at = now()
                        WHERE discord_guild_id = :guildId AND discord_user_id = :userId
                        """)
                .param("guildId", discordGuildId)
                .param("userId", discordUserId)
                .update();

        return previous.get();
    }

    public long currentAmount(long discordGuildId, long discordUserId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE((SELECT amount FROM balance
                                         WHERE discord_guild_id = :guildId AND discord_user_id = :userId), 0)
                        """)
                .param("guildId", discordGuildId)
                .param("userId", discordUserId)
                .query(Long.class)
                .single();
    }

    /**
     * Credits the same flat amount to every user.
     *
     * <p>Each user gets their own atomic upsert rather than one set-based statement.
     * A single {@code INSERT … SELECT unnest(…) … ON CONFLICT DO UPDATE} would be one
     * round trip instead of N, but it fails outright with "ON CONFLICT DO UPDATE
     * command cannot affect row a second time" if a user id appears twice — and
     * {@code /payout} runs a few times a day, so the round trips are not worth that
     * sharp edge. Callers pass a {@link Set}, so a duplicate mention cannot
     * double-credit anyone either way.
     *
     * <p>Caller is responsible for the surrounding transaction, so a failure part-way
     * through pays nobody rather than half the role.
     *
     * @return each user's resulting balance, keyed by user id
     */
    public Map<Long, Long> creditEach(long discordGuildId, Set<Long> userIds, long amountEach) {
        Map<Long, Long> results = new LinkedHashMap<>();
        for (Long userId : userIds) {
            results.put(userId, applyDelta(discordGuildId, userId, amountEach));
        }
        return results;
    }
}
