package personal.albiondiscordbot.albion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response from {@code /search?q=}.
 *
 * <p>The endpoint matches on substrings, so {@code q=Bob} also returns {@code Bobby}.
 * Callers must filter for an exact case-insensitive name match.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlbionSearchResponse(
        @JsonProperty("guilds") List<GuildHit> guilds,
        @JsonProperty("players") List<PlayerHit> players) {

    public List<GuildHit> guilds() {
        return guilds == null ? List.of() : guilds;
    }

    public List<PlayerHit> players() {
        return players == null ? List.of() : players;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerHit(
            @JsonProperty("Id") String id,
            @JsonProperty("Name") String name,
            @JsonProperty("GuildId") String guildId,
            @JsonProperty("GuildName") String guildName,
            @JsonProperty("AllianceId") String allianceId,
            @JsonProperty("AllianceName") String allianceName,
            @JsonProperty("KillFame") Long killFame,
            @JsonProperty("DeathFame") Long deathFame) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildHit(
            @JsonProperty("Id") String id,
            @JsonProperty("Name") String name,
            @JsonProperty("AllianceId") String allianceId,
            @JsonProperty("AllianceName") String allianceName,
            @JsonProperty("KillFame") Long killFame,
            @JsonProperty("DeathFame") Long deathFame) {
    }
}
