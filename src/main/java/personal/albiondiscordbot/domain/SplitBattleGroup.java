package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The battles one {@code /split-cta} preview covers.
 *
 * <p>Everything else a confirm button needs is encoded in its Discord custom id, which
 * caps at 100 characters — not enough for an open-ended list of battle ids. This is where
 * that list lives; the button carries only {@link #getGroupKey()}.
 *
 * <p>Rows are never cleaned up on purpose: a preview has to survive a restart, and the
 * Copy list button outlives the confirmation.
 */
@Entity
@Table(name = "split_battle_group")
public class SplitBattleGroup {

    @Id
    @Column(name = "group_key", length = 64)
    private String groupKey;

    @Column(name = "discord_guild_id", nullable = false)
    private Long discordGuildId;

    /** Comma-separated Albion battle ids, in the order the officer typed them. */
    @Column(name = "battle_ids", nullable = false)
    private String battleIds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SplitBattleGroup() {
        // for JPA
    }

    public SplitBattleGroup(String groupKey, long discordGuildId, List<Long> battleIds) {
        this.groupKey = groupKey;
        this.discordGuildId = discordGuildId;
        this.battleIds = battleIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public String getGroupKey() {
        return groupKey;
    }

    public Long getDiscordGuildId() {
        return discordGuildId;
    }

    /** The ids as stored, order preserved. */
    public List<Long> ids() {
        return Arrays.stream(battleIds.split(",")).map(String::trim).map(Long::valueOf).toList();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
