package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A battle from the Albion API. Global game data — one row per battle regardless of
 * how many Discord servers care about it.
 *
 * <p>The Albion battle id is the primary key, and that <em>is</em> the idempotency
 * guarantee: re-ingesting a battle the poller has already seen is an
 * {@code ON CONFLICT} no-op, which is what makes overlapping poll windows free.
 */
@Entity
@Table(name = "battle")
public class Battle {

    @Id
    @Column(name = "albion_battle_id")
    private Long albionBattleId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "total_fame", nullable = false)
    private long totalFame;

    @Column(name = "total_kills", nullable = false)
    private int totalKills;

    /** Size of the battle's {@code players} map; the CTA test compares against this. */
    @Column(name = "player_count", nullable = false)
    private int playerCount;

    @Column(name = "guild_count", nullable = false)
    private int guildCount;

    @Column(name = "alliance_count", nullable = false)
    private int allianceCount;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();

    protected Battle() {
        // for JPA
    }

    public Long getAlbionBattleId() {
        return albionBattleId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getTotalFame() {
        return totalFame;
    }

    public int getTotalKills() {
        return totalKills;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public int getGuildCount() {
        return guildCount;
    }

    public int getAllianceCount() {
        return allianceCount;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }
}
