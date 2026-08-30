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
     * Backs the friendly duplicate message. {@code ux_objective_name_time} is what
     * actually enforces it — two people relaying the same intel at the same moment both
     * pass this check.
     */
    boolean existsByDiscordGuildIdAndNameIgnoreCaseAndPopsAt(
            Long discordGuildId, String name, Instant popsAt);
}
