package personal.albiondiscordbot.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;

public interface TrackedAlbionGuildRepository extends JpaRepository<TrackedAlbionGuild, Long> {

    List<TrackedAlbionGuild> findByDiscordGuildId(Long discordGuildId);

    Optional<TrackedAlbionGuild> findByDiscordGuildIdAndAlbionGuildId(
            Long discordGuildId, String albionGuildId);

    boolean existsByDiscordGuildIdAndAlbionGuildId(Long discordGuildId, String albionGuildId);

    /** Every tracked Albion guild id across all Discord servers — the poller's filter. */
    @Query("SELECT DISTINCT t.albionGuildId FROM TrackedAlbionGuild t")
    List<String> findAllTrackedAlbionGuildIds();

    /** Discord servers that track the given Albion guild; used to fan out killboard posts. */
    @Query("SELECT DISTINCT t.discordGuildId FROM TrackedAlbionGuild t WHERE t.albionGuildId IN :albionGuildIds")
    List<Long> findDiscordGuildIdsTracking(List<String> albionGuildIds);
}
