package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Records that a battle has been posted to a Discord server's killboard channel.
 *
 * <p>Keyed per Discord server rather than stored as a flag on {@link Battle}, because
 * battles are global: two servers tracking guilds in the same fight must each get
 * their own post, and each needs its own message id. The primary key is what makes
 * posting exactly-once across restarts.
 */
@Entity
@Table(name = "killboard_post")
public class KillboardPost {

    @EmbeddedId
    private Key key;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt = Instant.now();

    protected KillboardPost() {
        // for JPA
    }

    public KillboardPost(Long discordGuildId, Long albionBattleId, Long messageId) {
        this.key = new Key(discordGuildId, albionBattleId);
        this.messageId = messageId;
    }

    public Key getKey() {
        return key;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "discord_guild_id", nullable = false)
        private Long discordGuildId;

        @Column(name = "albion_battle_id", nullable = false)
        private Long albionBattleId;

        protected Key() {
            // for JPA
        }

        public Key(Long discordGuildId, Long albionBattleId) {
            this.discordGuildId = discordGuildId;
            this.albionBattleId = albionBattleId;
        }

        public Long getDiscordGuildId() {
            return discordGuildId;
        }

        public Long getAlbionBattleId() {
            return albionBattleId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(discordGuildId, other.discordGuildId)
                    && Objects.equals(albionBattleId, other.albionBattleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(discordGuildId, albionBattleId);
        }
    }
}
