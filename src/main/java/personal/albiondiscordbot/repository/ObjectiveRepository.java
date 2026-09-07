package personal.albiondiscordbot.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import personal.albiondiscordbot.domain.Objective;

public interface ObjectiveRepository extends JpaRepository<Objective, Long> {

    /** One server's list, soonest first — the order {@code /objective show} prints. */
    List<Objective> findByDiscordGuildIdOrderByPopsAtAsc(Long discordGuildId);

    /**
     * Drops everything at or before {@code cutoff}.
     *
     * <p>At or before, not before: an objective at 10:00 is gone once the clock
     * <em>reaches</em> 10:30, so a row exactly one grace window old is expired rather
     * than surviving a further millisecond.
     *
     * <p>A bulk {@code DELETE} rather than the derived {@code deleteBy…}, which would load
     * every matching row into the persistence context first to delete them one at a time.
     */
    @Modifying
    @Query("DELETE FROM Objective o WHERE o.discordGuildId = :discordGuildId AND o.popsAt <= :cutoff")
    int deleteExpired(@Param("discordGuildId") Long discordGuildId, @Param("cutoff") Instant cutoff);

    /**
     * Whether some other line already holds this slot: same name, same zone, same instant.
     *
     * <p>Backs the friendly duplicate message. {@code ux_objective_slot} is what actually
     * enforces it — two people relaying the same intel at the same moment both pass this
     * check — so the expressions here are written to mirror that index exactly, {@code
     * lower()} and {@code coalesce()} included, or the check would pass things the insert
     * then refuses and the user would get the generic failure instead of the good message.
     *
     * <p>Both sides are lowered by Postgres rather than by the caller, so the comparison
     * cannot drift from the index over a locale Java and the database disagree about.
     *
     * @param zone the empty string when there is no zone, never {@code null}: a NULL
     *     parameter would compare unequal to everything, including the NULL column it is
     *     meant to match, and every zoneless duplicate would slip past
     * @param excludeId the row being edited, so an edit that leaves part of a line alone
     *     does not collide with itself; {@code 0} when adding, which no generated id takes
     */
    @Query(
            """
            SELECT COUNT(o) > 0 FROM Objective o
            WHERE o.discordGuildId = :discordGuildId
              AND lower(o.name) = lower(:name)
              AND lower(COALESCE(o.zone, '')) = lower(:zone)
              AND o.popsAt = :popsAt
              AND o.id <> :excludeId
            """)
    boolean slotTaken(
            @Param("discordGuildId") long discordGuildId,
            @Param("name") String name,
            @Param("zone") String zone,
            @Param("popsAt") Instant popsAt,
            @Param("excludeId") long excludeId);
}
