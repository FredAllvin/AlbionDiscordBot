package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** An in-game guild that counts as "ours" for this Discord server ({@code /guild add}). */
@Entity
@Table(name = "tracked_albion_guild")
public class TrackedAlbionGuild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_guild_id", nullable = false)
    private Long discordGuildId;

    @Column(name = "albion_guild_id", nullable = false, length = 64)
    private String albionGuildId;

    @Column(name = "albion_guild_name", nullable = false, length = 128)
    private String albionGuildName;

    @Column(name = "alliance_id", length = 64)
    private String allianceId;

    @Column(name = "alliance_tag", length = 32)
    private String allianceTag;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    @Column(name = "added_by_discord_user_id")
    private Long addedByDiscordUserId;

    protected TrackedAlbionGuild() {
        // for JPA
    }

    public TrackedAlbionGuild(
            Long discordGuildId,
            String albionGuildId,
            String albionGuildName,
            Long addedByDiscordUserId) {
        this.discordGuildId = discordGuildId;
        this.albionGuildId = albionGuildId;
        this.albionGuildName = albionGuildName;
        this.addedByDiscordUserId = addedByDiscordUserId;
    }

    public Long getId() {
        return id;
    }

    public Long getDiscordGuildId() {
        return discordGuildId;
    }

    public String getAlbionGuildId() {
        return albionGuildId;
    }

    public String getAlbionGuildName() {
        return albionGuildName;
    }

    public String getAllianceId() {
        return allianceId;
    }

    public void setAllianceId(String allianceId) {
        this.allianceId = allianceId;
    }

    public String getAllianceTag() {
        return allianceTag;
    }

    public void setAllianceTag(String allianceTag) {
        this.allianceTag = allianceTag;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Long getAddedByDiscordUserId() {
        return addedByDiscordUserId;
    }
}
