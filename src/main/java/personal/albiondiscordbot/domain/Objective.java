package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Something the guild means to be somewhere for at a given time — a chest, a fort, a
 * castle ({@code /objective add}).
 *
 * <p>{@link #getPopsAt()} is an absolute instant rather than the {@code HH:MM} that was
 * typed. A bare time of day cannot be ordered across midnight and cannot be expired: at
 * 23:55 UTC a 00:10 objective is fifteen minutes off and a 23:50 one is five minutes
 * past, and today's 20:00 an hour ago looks exactly like tomorrow's. The typed time is
 * recovered for display with {@link #popsAtUtc()}.
 */
@Entity
@Table(name = "objective")
public class Objective {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_guild_id", nullable = false)
    private Long discordGuildId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "pops_at", nullable = false)
    private Instant popsAt;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    @Column(name = "added_by_discord_user_id")
    private Long addedByDiscordUserId;

    protected Objective() {
        // for JPA
    }

    public Objective(Long discordGuildId, String name, Instant popsAt, Long addedByDiscordUserId) {
        this.discordGuildId = discordGuildId;
        this.name = name;
        this.popsAt = popsAt;
        this.addedByDiscordUserId = addedByDiscordUserId;
    }

    public Long getId() {
        return id;
    }

    public Long getDiscordGuildId() {
        return discordGuildId;
    }

    public String getName() {
        return name;
    }

    public Instant getPopsAt() {
        return popsAt;
    }

    /** The {@code HH:MM} UTC this was added as. */
    public LocalTime popsAtUtc() {
        return popsAt.atZone(ZoneOffset.UTC).toLocalTime();
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Long getAddedByDiscordUserId() {
        return addedByDiscordUserId;
    }
}
