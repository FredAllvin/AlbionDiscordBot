package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Watermark and health for a background poller. Seeded with the {@code battles} row. */
@Entity
@Table(name = "poller_state")
public class PollerState {

    public static final String BATTLES = "battles";

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /**
     * When the poller first stored a battle. {@code /stats} uses this to state its
     * true data window, since nothing before the bot's first run is recoverable.
     */
    @Column(name = "first_ingest_at")
    private Instant firstIngestAt;

    /**
     * Start time of the oldest battle the last run deferred because it was still
     * accruing participants, or null if nothing is outstanding.
     *
     * <p>This is what keeps a long fight reachable. The watermark advances every run
     * including the runs that deliberately skipped an unfinalized battle, so without
     * this the battle has to finalize before the overlap slides past its start time —
     * a race that big battles lose, because they are the ones that stay open longest.
     */
    @Column(name = "oldest_open_battle_at")
    private Instant oldestOpenBattleAt;

    protected PollerState() {
        // for JPA
    }

    public void recordRun() {
        this.lastRunAt = Instant.now();
    }

    public void recordSuccess() {
        Instant now = Instant.now();
        this.lastRunAt = now;
        this.lastSuccessAt = now;
        this.consecutiveFailures = 0;
    }

    public void recordFailure() {
        this.lastRunAt = Instant.now();
        this.consecutiveFailures++;
    }

    /**
     * Records what the run just finished still owes, recomputed from scratch every time.
     * Pass null when nothing was deferred, which is how the floor clears itself once the
     * battle finalizes and is ingested.
     */
    public void setOldestOpenBattleAt(Instant oldestOpenBattleAt) {
        this.oldestOpenBattleAt = oldestOpenBattleAt;
    }

    public void markFirstIngestIfAbsent() {
        if (this.firstIngestAt == null) {
            this.firstIngestAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getFirstIngestAt() {
        return firstIngestAt;
    }

    public Instant getOldestOpenBattleAt() {
        return oldestOpenBattleAt;
    }
}
