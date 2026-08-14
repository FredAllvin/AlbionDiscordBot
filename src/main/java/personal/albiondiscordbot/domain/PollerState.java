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
}
