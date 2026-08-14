package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A user's silver balance.
 *
 * <p>Read-only from JPA's point of view: every mutation goes through
 * {@code BalanceDao} as a single atomic SQL statement, so there is deliberately no
 * {@code @Version} column here — row locks in the database provide the
 * serialisation, and optimistic locking would only add retry storms under
 * {@code /payout}.
 */
@Entity
@Table(name = "balance")
public class Balance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_guild_id", nullable = false)
    private Long discordGuildId;

    @Column(name = "discord_user_id", nullable = false)
    private Long discordUserId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Balance() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getDiscordGuildId() {
        return discordGuildId;
    }

    public Long getDiscordUserId() {
        return discordUserId;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
