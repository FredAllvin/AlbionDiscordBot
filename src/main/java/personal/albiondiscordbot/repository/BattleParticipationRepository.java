package personal.albiondiscordbot.repository;

import java.time.Instant;
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
     * How many of <em>our</em> guild members fought in a battle. Participation rows only
     * exist for tracked guilds, so this excludes the enemy side.
     */
    long countByAlbionBattleId(Long albionBattleId);

    List<BattleParticipation> findByAlbionBattleId(Long albionBattleId);
}
