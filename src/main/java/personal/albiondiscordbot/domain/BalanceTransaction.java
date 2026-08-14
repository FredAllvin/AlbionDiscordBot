package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only ledger entry. One row is written in the same transaction as every
 * balance mutation, so any balance can be reconstructed and audited.
 */
@Entity
@Table(name = "balance_transaction")
public class BalanceTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_guild_id", nullable = false)
    private Long discordGuildId;

    /** The user whose balance moved. */
    @Column(name = "discord_user_id", nullable = false)
    private Long discordUserId;

    /** Who ran the command; null for system-initiated changes. */
    @Column(name = "actor_discord_user_id")
    private Long actorDiscordUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private TransactionType type;

    /** Signed: negative for debits. */
    @Column(name = "delta", nullable = false)
    private long delta;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    /** The other party in a transfer. */
    @Column(name = "counterparty_discord_user_id")
    private Long counterpartyDiscordUserId;

    /** Payout batch id or role id, so one {@code /payout} can be reconstructed. */
    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BalanceTransaction() {
        // for JPA
    }

    public BalanceTransaction(
            Long discordGuildId,
            Long discordUserId,
            Long actorDiscordUserId,
            TransactionType type,
            long delta,
            long balanceAfter) {
        this.discordGuildId = discordGuildId;
        this.discordUserId = discordUserId;
        this.actorDiscordUserId = actorDiscordUserId;
        this.type = type;
        this.delta = delta;
        this.balanceAfter = balanceAfter;
    }

    public BalanceTransaction withCounterparty(Long counterpartyDiscordUserId) {
        this.counterpartyDiscordUserId = counterpartyDiscordUserId;
        return this;
    }

    public BalanceTransaction withReference(String reference) {
        this.reference = reference;
        return this;
    }

    public BalanceTransaction withNote(String note) {
        this.note = note;
        return this;
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

    public Long getActorDiscordUserId() {
        return actorDiscordUserId;
    }

    public TransactionType getType() {
        return type;
    }

    public long getDelta() {
        return delta;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Long getCounterpartyDiscordUserId() {
        return counterpartyDiscordUserId;
    }

    public String getReference() {
        return reference;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
