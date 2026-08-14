package personal.albiondiscordbot.poller;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.albion.dto.AlbionBattle;

/**
 * Stores battles and the participation rows for tracked-guild members.
 *
 * <p>Every write is an upsert keyed on ids the Albion API already assigns, so ingesting
 * the same battle twice is a no-op rather than a double count. That is what makes the
 * poller's overlapping scan windows free, and it is why {@code /stats} aggregates these
 * rows instead of maintaining counters that could drift.
 */
@Service
public class BattleIngestService {

    private final JdbcClient jdbc;

    public BattleIngestService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param trackedGuildIds Albion guild ids worth storing participants for
     * @return true if this battle had not been stored before
     */
    @Transactional
    public boolean ingest(AlbionBattle battle, Set<String> trackedGuildIds) {
        // "xmax = 0" is the Postgres idiom for "this upsert actually inserted".
        // On a conflict-update xmax holds the locking transaction id and is non-zero,
        // which distinguishes a first sighting from a re-scan exactly, with no
        // timestamp guesswork.
        boolean firstSighting = Boolean.TRUE.equals(jdbc.sql(
                        """
                        INSERT INTO battle (albion_battle_id, start_time, end_time, total_fame,
                                            total_kills, player_count, guild_count, alliance_count, ingested_at)
                        VALUES (:id, :startTime, :endTime, :totalFame,
                                :totalKills, :playerCount, :guildCount, :allianceCount, now())
                        ON CONFLICT (albion_battle_id) DO UPDATE SET
                            end_time = EXCLUDED.end_time,
                            total_fame = EXCLUDED.total_fame,
                            total_kills = EXCLUDED.total_kills,
                            player_count = EXCLUDED.player_count,
                            guild_count = EXCLUDED.guild_count,
                            alliance_count = EXCLUDED.alliance_count
                        RETURNING (xmax = 0)
                        """)
                .param("id", battle.id())
                .param("startTime", timestamp(battle.startTime()))
                .param("endTime", timestamp(battle.endTime()))
                .param("totalFame", battle.totalFame())
                .param("totalKills", battle.totalKills())
                .param("playerCount", battle.playerCount())
                .param("guildCount", battle.guilds().size())
                .param("allianceCount", battle.alliances().size())
                .query(Boolean.class)
                .single());

        for (AlbionBattle.Participant participant : battle.players().values()) {
            if (participant.guildId() == null || !trackedGuildIds.contains(participant.guildId())) {
                continue;
            }
            jdbc.sql(
                            """
                            INSERT INTO battle_participation (
                                albion_battle_id, albion_player_id, albion_player_name,
                                albion_guild_id, albion_guild_name, kills, deaths, kill_fame,
                                battle_start_time, battle_player_count)
                            VALUES (:battleId, :playerId, :playerName,
                                    :guildId, :guildName, :kills, :deaths, :killFame,
                                    :startTime, :playerCount)
                            ON CONFLICT (albion_battle_id, albion_player_id) DO UPDATE SET
                                albion_player_name = EXCLUDED.albion_player_name,
                                albion_guild_id = EXCLUDED.albion_guild_id,
                                albion_guild_name = EXCLUDED.albion_guild_name,
                                kills = EXCLUDED.kills,
                                deaths = EXCLUDED.deaths,
                                kill_fame = EXCLUDED.kill_fame,
                                battle_player_count = EXCLUDED.battle_player_count
                            """)
                    .param("battleId", battle.id())
                    .param("playerId", participant.id())
                    .param("playerName", participant.name())
                    .param("guildId", participant.guildId())
                    .param("guildName", participant.guildName())
                    .param("kills", participant.kills())
                    .param("deaths", participant.deaths())
                    .param("killFame", participant.killFame())
                    .param("startTime", timestamp(battle.startTime()))
                    .param("playerCount", battle.playerCount())
                    .update();
        }

        return firstSighting;
    }

    /**
     * The Postgres JDBC driver cannot bind {@link Instant} directly; timestamptz
     * columns need an {@link OffsetDateTime}.
     */
    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** Whether any tracked-guild member fought in this battle. */
    @Transactional(readOnly = true)
    public boolean hasTrackedParticipants(long albionBattleId) {
        Long count = jdbc.sql("SELECT count(*) FROM battle_participation WHERE albion_battle_id = :id")
                .param("id", albionBattleId)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }
}
