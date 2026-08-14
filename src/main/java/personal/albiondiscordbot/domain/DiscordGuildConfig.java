package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Per-Discord-server configuration, written by {@code /setup}. */
@Entity
@Table(name = "discord_guild_config")
public class DiscordGuildConfig {

    /** Natural key: the Discord server (guild) snowflake. */
    @Id
    @Column(name = "discord_guild_id")
    private Long discordGuildId;

    @Column(name = "staff_role_id")
    private Long staffRoleId;

    @Column(name = "verified_role_id")
    private Long verifiedRoleId;

    @Column(name = "log_channel_id")
    private Long logChannelId;

    @Column(name = "killboard_channel_id")
    private Long killboardChannelId;

    @Column(name = "cta_min_players", nullable = false)
    private int ctaMinPlayers = 10;

    @Column(name = "setup_completed", nullable = false)
    private boolean setupCompleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DiscordGuildConfig() {
        // for JPA
    }

    public DiscordGuildConfig(Long discordGuildId) {
        this.discordGuildId = discordGuildId;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getDiscordGuildId() {
        return discordGuildId;
    }

    public Long getStaffRoleId() {
        return staffRoleId;
    }

    public void setStaffRoleId(Long staffRoleId) {
        this.staffRoleId = staffRoleId;
    }

    public Long getVerifiedRoleId() {
        return verifiedRoleId;
    }

    public void setVerifiedRoleId(Long verifiedRoleId) {
        this.verifiedRoleId = verifiedRoleId;
    }

    public Long getLogChannelId() {
        return logChannelId;
    }

    public void setLogChannelId(Long logChannelId) {
        this.logChannelId = logChannelId;
    }

    public Long getKillboardChannelId() {
        return killboardChannelId;
    }

    public void setKillboardChannelId(Long killboardChannelId) {
        this.killboardChannelId = killboardChannelId;
    }

    public int getCtaMinPlayers() {
        return ctaMinPlayers;
    }

    public void setCtaMinPlayers(int ctaMinPlayers) {
        this.ctaMinPlayers = ctaMinPlayers;
    }

    public boolean isSetupCompleted() {
        return setupCompleted;
    }

    public void setSetupCompleted(boolean setupCompleted) {
        this.setupCompleted = setupCompleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
