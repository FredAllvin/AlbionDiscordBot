package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * Links a Discord user to an Albion character.
 *
 * <p>Unregistering is a soft delete ({@code active = false}) so the audit trail and
 * re-registration history survive. Two partial unique indexes on {@code active} rows
 * enforce one character per user and one user per character.
 *
 * <p>Note this proves a character exists and is in a tracked guild — it cannot prove
 * the Discord user actually owns it. That is what {@code forceRegisteredBy} and
 * {@code /unregister} exist for.
 */
@Entity
@Table(name = "registration")
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_guild_id", nullable = false)
    private Long discordGuildId;

    @Column(name = "discord_user_id", nullable = false)
    private Long discordUserId;

    @Column(name = "albion_player_id", nullable = false, length = 64)
    private String albionPlayerId;

    @Column(name = "albion_player_name", nullable = false, length = 64)
    private String albionPlayerName;

    /** Lower-cased copy, since Albion name matching is case-insensitive. */
    @Column(name = "albion_player_name_lower", nullable = false, length = 64)
    private String albionPlayerNameLower;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    /** False when force-registered: guild membership was never confirmed. */
    @Column(name = "verified", nullable = false)
    private boolean verified = true;

    @Column(name = "force_registered_by")
    private Long forceRegisteredBy;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "unregistered_at")
    private Instant unregisteredAt;

    @Column(name = "unregistered_by")
    private Long unregisteredBy;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "last_validation_ok")
    private Boolean lastValidationOk;

    /** Fame baseline captured at registration; the origin for /stats deltas. */
    @Embedded
    private FameBaseline fameBaseline = FameBaseline.unavailable();

    protected Registration() {
        // for JPA
    }

    public Registration(
            Long discordGuildId, Long discordUserId, String albionPlayerId, String albionPlayerName) {
        this.discordGuildId = discordGuildId;
        this.discordUserId = discordUserId;
        this.albionPlayerId = albionPlayerId;
        this.albionPlayerName = albionPlayerName;
        this.albionPlayerNameLower = albionPlayerName.toLowerCase(Locale.ROOT);
    }

    public void deactivate(Long actorDiscordUserId) {
        this.active = false;
        this.unregisteredAt = Instant.now();
        this.unregisteredBy = actorDiscordUserId;
    }

    public void recordValidation(boolean ok) {
        this.lastValidatedAt = Instant.now();
        this.lastValidationOk = ok;
    }

    public void markForceRegistered(Long actorDiscordUserId) {
        this.verified = false;
        this.forceRegisteredBy = actorDiscordUserId;
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

    public String getAlbionPlayerId() {
        return albionPlayerId;
    }

    public String getAlbionPlayerName() {
        return albionPlayerName;
    }

    public String getAlbionPlayerNameLower() {
        return albionPlayerNameLower;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public boolean isVerified() {
        return verified;
    }

    public Long getForceRegisteredBy() {
        return forceRegisteredBy;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUnregisteredAt() {
        return unregisteredAt;
    }

    public Long getUnregisteredBy() {
        return unregisteredBy;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }

    public Boolean getLastValidationOk() {
        return lastValidationOk;
    }

    public FameBaseline getFameBaseline() {
        return fameBaseline;
    }

    public void setFameBaseline(FameBaseline fameBaseline) {
        this.fameBaseline = fameBaseline;
    }
}
