package personal.albiondiscordbot.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import personal.albiondiscordbot.domain.Battle;

public interface BattleRepository extends JpaRepository<Battle, Long> {

    Optional<Battle> findByAlbionBattleId(Long albionBattleId);

    /**
     * The most recent CTA-sized battle that one of our guilds actually fought in, so
     * {@code /payout-cta} can default to "the fight we just had".
     *
     * <p>The {@code EXISTS} clause is what restricts this to our battles: participation
     * rows are only stored for tracked guilds.
     */
    @Query(
            value =
                    """
                    SELECT b.* FROM battle b
                    WHERE b.player_count >= :minTotalPlayers
                      AND EXISTS (SELECT 1 FROM battle_participation p
                                  WHERE p.albion_battle_id = b.albion_battle_id
                                    AND p.guild_player_count >= :minGuildPlayers)
                    ORDER BY b.start_time DESC
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<Battle> findLatestCta(int minTotalPlayers, int minGuildPlayers);
}
