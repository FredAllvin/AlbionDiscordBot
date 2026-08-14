package personal.albiondiscordbot.albion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * One battle from {@code /battles}.
 *
 * <p>The list endpoint returns the <strong>complete</strong> {@code players} map — a
 * 354-player battle was verified to return all 354 entries, identical to
 * {@code /battles/{id}} — so the poller never needs per-battle detail calls.
 *
 * <p>{@code clusterName} is always null in practice: the API does not expose where a
 * battle happened, so nothing should be built assuming a zone name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlbionBattle(
        @JsonProperty("id") long id,
        @JsonProperty("startTime") Instant startTime,
        @JsonProperty("endTime") Instant endTime,
        @JsonProperty("timeout") Instant timeout,
        @JsonProperty("totalFame") long totalFame,
        @JsonProperty("totalKills") int totalKills,
        @JsonProperty("clusterName") String clusterName,
        @JsonProperty("players") Map<String, Participant> players,
        @JsonProperty("guilds") Map<String, Side> guilds,
        @JsonProperty("alliances") Map<String, Side> alliances) {

    public Map<String, Participant> players() {
        return players == null ? Map.of() : players;
    }

    public Map<String, Side> guilds() {
        return guilds == null ? Map.of() : guilds;
    }

    public Map<String, Side> alliances() {
        return alliances == null ? Map.of() : alliances;
    }

    public int playerCount() {
        return players().size();
    }

    /**
     * Whether the battle has stopped accruing participants. Battles stay open for
     * roughly 180 seconds after their last kill, and ingesting before then records
     * partial data.
     */
    public boolean isClosed(Instant now) {
        return timeout != null && timeout.isBefore(now);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Participant(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("kills") int kills,
            @JsonProperty("deaths") int deaths,
            @JsonProperty("killFame") long killFame,
            @JsonProperty("guildId") String guildId,
            @JsonProperty("guildName") String guildName,
            @JsonProperty("allianceId") String allianceId,
            @JsonProperty("allianceName") String allianceName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Side(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("kills") int kills,
            @JsonProperty("deaths") int deaths,
            @JsonProperty("killFame") long killFame,
            @JsonProperty("alliance") String alliance,
            @JsonProperty("allianceId") String allianceId) {
    }
}
