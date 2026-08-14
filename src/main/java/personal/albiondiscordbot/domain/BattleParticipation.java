package personal.albiondiscordbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One player's result in one battle.
 *
 * <p>Only players belonging to a tracked guild are stored, which keeps this table
 * proportional to our own guild rather than the whole EU server.
 *
 * <p>Caveat that {@code /stats} surfaces to users: the Albion API only lists players
 * who scored a kill, died, or earned assist fame. Someone present who contributed
 * nothing does not appear, so CTA attendance is a lower bound.
 */
@Entity
@Table(name = "battle_participation")
public class BattleParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "albion_battle_id", nullable = false)
    private Long albionBattleId;

    @Column(name = "albion_player_id", nullable = false, length = 64)
    private String albionPlayerId;

    /** Name as at the battle; players rename. */
    @Column(name = "albion_player_name", nullable = false, length = 64)
    private String albionPlayerName;

    /** Guild as at the battle, so transfers do not rewrite history. */
    @Column(name = "albion_guild_id", length = 64)
    private String albionGuildId;

    @Column(name = "albion_guild_name", length = 128)
    private String albionGuildName;

    @Column(name = "kills", nullable = false)
    private int kills;

    @Column(name = "deaths", nullable = false)
    private int deaths;

    @Column(name = "kill_fame", nullable = false)
    private long killFame;

    /** Denormalised from {@link Battle} so /stats is one indexed scan. Immutable. */
    @Column(name = "battle_start_time", nullable = false)
    private Instant battleStartTime;

    /**
     * Total size of the battle, both sides. Kept for context — it is what the killboard
     * embed reports — but it is <em>not</em> what decides whether a battle counts as a
     * CTA.
     */
    @Column(name = "battle_player_count", nullable = false)
    private int battlePlayerCount;

    /**
     * How many tracked-guild members fought in this battle — the number the CTA
     * threshold is compared against.
     *
     * <p>Denormalised rather than derived, because the threshold is configurable per
     * Discord server and so has to be applied at query time. Measuring our own turnout
     * rather than total battle size is what stops a small party swept up in someone
     * else's brawl from counting as a guild call to arms.
     */
    @Column(name = "guild_player_count", nullable = false)
    private int guildPlayerCount;

    protected BattleParticipation() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getAlbionBattleId() {
        return albionBattleId;
    }

    public String getAlbionPlayerId() {
        return albionPlayerId;
    }

    public String getAlbionPlayerName() {
        return albionPlayerName;
    }

    public String getAlbionGuildId() {
        return albionGuildId;
    }

    public String getAlbionGuildName() {
        return albionGuildName;
    }

    public int getKills() {
        return kills;
    }

    public int getDeaths() {
        return deaths;
    }

    public long getKillFame() {
        return killFame;
    }

    public Instant getBattleStartTime() {
        return battleStartTime;
    }

    public int getBattlePlayerCount() {
        return battlePlayerCount;
    }

    public int getGuildPlayerCount() {
        return guildPlayerCount;
    }
}
