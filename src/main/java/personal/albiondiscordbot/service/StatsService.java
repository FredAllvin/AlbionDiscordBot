package personal.albiondiscordbot.service;

import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionPlayerDetail;
import personal.albiondiscordbot.domain.FameBaseline;
import personal.albiondiscordbot.domain.PlayerFameSnapshot;
import personal.albiondiscordbot.domain.PollerState;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.repository.PlayerFameSnapshotRepository;
import personal.albiondiscordbot.repository.PollerStateRepository;

/**
 * Builds a player's stats from two independent sources, deliberately kept separate.
 *
 * <p>Battle counts come from {@code battle_participation}; fame totals come from the
 * difference between the registration snapshot and the live API. The two overlap —
 * battle kill fame is a <em>subset</em> of total kill fame — so they are reported side
 * by side and never added together.
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final JdbcClient jdbc;
    private final AlbionApiClient albion;
    private final PollerStateRepository pollerStates;
    private final PlayerFameSnapshotRepository snapshots;

    public StatsService(
            JdbcClient jdbc,
            AlbionApiClient albion,
            PollerStateRepository pollerStates,
            PlayerFameSnapshotRepository snapshots) {
        this.jdbc = jdbc;
        this.albion = albion;
        this.pollerStates = pollerStates;
        this.snapshots = snapshots;
    }

    @Transactional
    public PlayerStats compute(Registration registration, int ctaThreshold) {
        Instant trackingSince = trackingWindowStart(registration);

        BattleTotals totals = jdbc.sql(
                        """
                        SELECT COALESCE(SUM(kills), 0)                                              AS kills,
                               COALESCE(SUM(deaths), 0)                                             AS deaths,
                               COALESCE(SUM(kill_fame), 0)                                          AS kill_fame,
                               COUNT(*)                                                             AS battles,
                               COUNT(*) FILTER (WHERE battle_player_count > :ctaThreshold)          AS ctas
                        FROM battle_participation
                        WHERE albion_player_id = :playerId
                          AND battle_start_time >= :since
                        """)
                .param("playerId", registration.getAlbionPlayerId())
                // The Postgres driver cannot bind Instant to timestamptz directly.
                .param("since", trackingSince.atOffset(java.time.ZoneOffset.UTC))
                .param("ctaThreshold", ctaThreshold)
                .query((rs, rowNum) -> new BattleTotals(
                        rs.getLong("kills"),
                        rs.getLong("deaths"),
                        rs.getLong("kill_fame"),
                        rs.getLong("battles"),
                        rs.getLong("ctas")))
                .single();

        FameDelta fame = fameDelta(registration);

        return new PlayerStats(
                registration.getAlbionPlayerName(),
                trackingSince,
                registration.isVerified(),
                totals,
                fame);
    }

    /**
     * Stats can only cover battles the poller actually saw, so the window starts at
     * whichever is later: when the player registered, or when the poller first stored
     * anything. Reporting from the registration date alone would imply coverage the
     * bot never had.
     */
    private Instant trackingWindowStart(Registration registration) {
        Instant registered = registration.getRegisteredAt();
        Instant firstIngest = pollerStates
                .findById(PollerState.BATTLES)
                .map(PollerState::getFirstIngestAt)
                .orElse(null);

        if (firstIngest == null) {
            return registered;
        }
        return firstIngest.isAfter(registered) ? firstIngest : registered;
    }

    private FameDelta fameDelta(Registration registration) {
        FameBaseline baseline = registration.getFameBaseline();
        if (baseline == null || !baseline.isAvailable()) {
            return FameDelta.unavailable();
        }
        Optional<AlbionPlayerDetail> current;
        try {
            current = albion.getPlayer(registration.getAlbionPlayerId());
        } catch (RuntimeException e) {
            log.warn("Could not refresh fame for {}", registration.getAlbionPlayerName(), e);
            return FameDelta.unavailable();
        }
        if (current.isEmpty()) {
            return FameDelta.unavailable();
        }
        AlbionPlayerDetail detail = current.get();
        recordSnapshot(detail);
        return new FameDelta(
                true,
                detail.killFameOrZero() - orZero(baseline.getKillFame()),
                detail.deathFameOrZero() - orZero(baseline.getDeathFame()),
                detail.pveFame() - orZero(baseline.getPveFame()),
                detail.gatheringFame() - orZero(baseline.getGatheringFame()),
                detail.craftingFame() - orZero(baseline.getCraftingFame()),
                detail.guildName());
    }

    private long orZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * Stores the freshly-read fame totals.
     *
     * <p>The delta shown to users is computed against the registration baseline, so this
     * is not needed to answer the current question — it accumulates a history so fame
     * over time can be charted later without a schema change. Deliberately best-effort:
     * failing to record history must never break {@code /stats}.
     */
    private void recordSnapshot(AlbionPlayerDetail detail) {
        try {
            snapshots.save(new PlayerFameSnapshot(
                    detail.id(),
                    detail.killFameOrZero(),
                    detail.deathFameOrZero(),
                    detail.pveFame(),
                    detail.gatheringFame(),
                    detail.craftingFame(),
                    detail.fishingFame(),
                    detail.farmingFame()));
        } catch (RuntimeException e) {
            log.warn("Could not store fame snapshot for {}", detail.name(), e);
        }
    }

    public record BattleTotals(long kills, long deaths, long killFame, long battles, long ctas) {

        public String killDeathRatio() {
            if (deaths == 0) {
                return kills == 0 ? "—" : "%d.00".formatted(kills);
            }
            return "%.2f".formatted((double) kills / deaths);
        }
    }

    public record FameDelta(
            boolean available,
            long killFame,
            long deathFame,
            long pveFame,
            long gatheringFame,
            long craftingFame,
            String currentGuildName) {

        static FameDelta unavailable() {
            return new FameDelta(false, 0, 0, 0, 0, 0, null);
        }
    }

    public record PlayerStats(
            String characterName,
            Instant trackedSince,
            boolean verified,
            BattleTotals battles,
            FameDelta fame) {
    }
}
