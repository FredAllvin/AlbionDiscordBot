package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A periodic capture of a player's fame totals. The delta against the registration
 * baseline is what {@code /stats} reports as fame earned since joining.
 */
@Entity
@Table(name = "player_fame_snapshot")
public class PlayerFameSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "albion_player_id", nullable = false, length = 64)
    private String albionPlayerId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();

    @Column(name = "kill_fame", nullable = false)
    private long killFame;

    @Column(name = "death_fame", nullable = false)
    private long deathFame;

    @Column(name = "pve_fame", nullable = false)
    private long pveFame;

    @Column(name = "gathering_fame", nullable = false)
    private long gatheringFame;

    @Column(name = "crafting_fame", nullable = false)
    private long craftingFame;

    @Column(name = "fishing_fame", nullable = false)
    private long fishingFame;

    @Column(name = "farming_fame", nullable = false)
    private long farmingFame;

    protected PlayerFameSnapshot() {
        // for JPA
    }

    public PlayerFameSnapshot(
            String albionPlayerId,
            long killFame,
            long deathFame,
            long pveFame,
            long gatheringFame,
            long craftingFame,
            long fishingFame,
            long farmingFame) {
        this.albionPlayerId = albionPlayerId;
        this.killFame = killFame;
        this.deathFame = deathFame;
        this.pveFame = pveFame;
        this.gatheringFame = gatheringFame;
        this.craftingFame = craftingFame;
        this.fishingFame = fishingFame;
        this.farmingFame = farmingFame;
    }

    public Long getId() {
        return id;
    }

    public String getAlbionPlayerId() {
        return albionPlayerId;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public long getKillFame() {
        return killFame;
    }

    public long getDeathFame() {
        return deathFame;
    }

    public long getPveFame() {
        return pveFame;
    }

    public long getGatheringFame() {
        return gatheringFame;
    }

    public long getCraftingFame() {
        return craftingFame;
    }

    public long getFishingFame() {
        return fishingFame;
    }

    public long getFarmingFame() {
        return farmingFame;
    }
}
