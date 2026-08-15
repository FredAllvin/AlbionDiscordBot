package personal.albiondiscordbot.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Makes one confirmation button usable exactly once.
 *
 * <p>The claim is taken inside the same transaction as the money it guards, so there is
 * no window where a batch is credited but not yet marked as claimed. A second click
 * therefore either loses the race and is refused, or arrives later and finds the row.
 */
@Repository
public class BatchClaimDao {

    private final JdbcClient jdbc;

    public BatchClaimDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that this confirmation has been acted on.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a prior {@code SELECT}: the check and
     * the claim have to be one statement, or two clicks land either side of the gap
     * between them — which is the whole bug this exists to close.
     *
     * @return false if the token had already been claimed, meaning the caller must not
     *     move any silver
     */
    public boolean claim(String claimToken, long discordGuildId, String batchId) {
        int inserted = jdbc.sql(
                        """
                        INSERT INTO batch_claim (claim_token, discord_guild_id, batch_id, claimed_at)
                        VALUES (:token, :guildId, :batchId, now())
                        ON CONFLICT (claim_token) DO NOTHING
                        """)
                .param("token", claimToken)
                .param("guildId", discordGuildId)
                .param("batchId", batchId)
                .update();

        return inserted == 1;
    }
}
