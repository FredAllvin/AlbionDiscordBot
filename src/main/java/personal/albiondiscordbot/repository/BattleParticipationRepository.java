package personal.albiondiscordbot.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import personal.albiondiscordbot.domain.BattleParticipation;

public interface BattleParticipationRepository extends JpaRepository<BattleParticipation, Long> {

    /**
     * Aggregates a player's battle record.
     *
     * <p>{@code /stats} derives its numbers from this query rather than from
     * incremented counters. That is what makes the poller idempotent by construction:
     * there is no counter to double-increment, so re-ingesting a battle cannot inflate
     * anyone's stats.
     *
     * <p>The CTA test is strictly greater-than, matching "a fight bigger than 30".
     */
    @Query("""
            SELECT COALESCE(SUM(p.kills), 0),
                   COALESCE(SUM(p.deaths), 0),
                   COALESCE(SUM(p.killFame), 0),
                   COUNT(p),
                   COALESCE(SUM(CASE WHEN p.battlePlayerCount > :ctaThreshold THEN 1 ELSE 0 END), 0)
            FROM BattleParticipation p
            WHERE p.albionPlayerId = :albionPlayerId
              AND p.battleStartTime >= :since
            """)
    Object[] aggregateFor(String albionPlayerId, Instant since, int ctaThreshold);

    boolean existsByAlbionBattleId(Long albionBattleId);

    /**
     * How many distinct guild members fought across these battles. Participation rows
     * only exist for tracked guilds, so this excludes the enemy side.
     *
     * <p>Distinct on the player, not a row count: someone who fought in two of a CTA's
     * three battles is one person who turned up, and this figure is compared against the
     * number of recipients to report how many could not be credited.
     */
    @Query("""
            SELECT COUNT(DISTINCT p.albionPlayerId) FROM BattleParticipation p
            WHERE p.albionBattleId IN :albionBattleIds
            """)
    long countFightersIn(Collection<Long> albionBattleIds);

    List<BattleParticipation> findByAlbionBattleId(Long albionBattleId);
}
