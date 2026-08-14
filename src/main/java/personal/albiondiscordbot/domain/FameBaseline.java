package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

/**
 * A player's fame totals captured at registration. {@code /stats} subtracts this from
 * the current values to get "since joining" figures.
 *
 * <p>{@code available} is false when a force-registration could not reach the Albion
 * API. In that case {@code /stats} reports "baseline unavailable" rather than showing
 * a number that would really be the character's lifetime total.
 */
@Embeddable
public class FameBaseline {

    @Column(name = "snapshot_available", nullable = false)
    private boolean available;

    @Column(name = "snapshot_taken_at")
    private Instant takenAt;

    @Column(name = "snapshot_kill_fame")
    private Long killFame;

    @Column(name = "snapshot_death_fame")
    private Long deathFame;

    @Column(name = "snapshot_pve_fame")
    private Long pveFame;

    @Column(name = "snapshot_gathering_fame")
    private Long gatheringFame;

    @Column(name = "snapshot_crafting_fame")
    private Long craftingFame;

    @Column(name = "snapshot_fishing_fame")
    private Long fishingFame;

    @Column(name = "snapshot_farming_fame")
    private Long farmingFame;

    protected FameBaseline() {
        // for JPA
    }

    public static FameBaseline unavailable() {
        return new FameBaseline();
    }

    public static FameBaseline of(
            long killFame,
            long deathFame,
            long pveFame,
            long gatheringFame,
            long craftingFame,
            long fishingFame,
            long farmingFame) {
        FameBaseline b = new FameBaseline();
        b.available = true;
        b.takenAt = Instant.now();
        b.killFame = killFame;
        b.deathFame = deathFame;
        b.pveFame = pveFame;
        b.gatheringFame = gatheringFame;
        b.craftingFame = craftingFame;
        b.fishingFame = fishingFame;
        b.farmingFame = farmingFame;
        return b;
    }

    public boolean isAvailable() {
        return available;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public Long getKillFame() {
        return killFame;
    }

    public Long getDeathFame() {
        return deathFame;
    }

    public Long getPveFame() {
        return pveFame;
    }

    public Long getGatheringFame() {
        return gatheringFame;
    }

    public Long getCraftingFame() {
        return craftingFame;
    }

    public Long getFishingFame() {
        return fishingFame;
    }

    public Long getFarmingFame() {
        return farmingFame;
    }
}
