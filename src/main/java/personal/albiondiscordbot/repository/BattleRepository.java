package personal.albiondiscordbot.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import personal.albiondiscordbot.domain.Battle;

public interface BattleRepository extends JpaRepository<Battle, Long> {

    Optional<Battle> findByAlbionBattleId(Long albionBattleId);

    /**
     * The most recent CTA-sized battle that <em>this Discord server's</em> guilds fought
     * in, so {@code /split-cta} can default to "the fight we just had".
     *
     * <p>The turnout is counted by joining participation back to this server's tracked
     * guilds rather than reading {@code guild_player_count}. That column counts every
     * tracked guild across every Discord server, because {@code battle_participation} is
     * global game data — so with two servers tracking different guilds it would let one
     * server's fight satisfy the other server's CTA rule. Counting through
     * {@code tracked_albion_guild} gives the same answer on a single server and the right
     * one on several.
     */
    @Query(
            value =
                    """
                    SELECT b.* FROM battle b
                    WHERE b.player_count >= :minTotalPlayers
                      AND (SELECT count(*) FROM battle_participation p
                           JOIN tracked_albion_guild t
                             ON t.albion_guild_id = p.albion_guild_id
                           WHERE p.albion_battle_id = b.albion_battle_id
                             AND t.discord_guild_id = :discordGuildId) >= :minGuildPlayers
                    ORDER BY b.start_time DESC
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<Battle> findLatestCta(long discordGuildId, int minTotalPlayers, int minGuildPlayers);
}
