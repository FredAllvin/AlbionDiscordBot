package personal.albiondiscordbot.albion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from {@code /guilds/{id}}.
 *
 * <p>Note {@code killFame} is lower-cased here while {@code /players/{id}} uses
 * {@code KillFame}. The Albion API is inconsistent about this; the explicit
 * annotations are what keep it from silently deserialising to null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlbionGuildDetail(
        @JsonProperty("Id") String id,
        @JsonProperty("Name") String name,
        @JsonProperty("FounderName") String founderName,
        @JsonProperty("Founded") String founded,
        @JsonProperty("AllianceId") String allianceId,
        @JsonProperty("AllianceName") String allianceName,
        @JsonProperty("AllianceTag") String allianceTag,
        @JsonProperty("killFame") Long killFame,
        @JsonProperty("DeathFame") Long deathFame,
        @JsonProperty("MemberCount") Integer memberCount) {
}
