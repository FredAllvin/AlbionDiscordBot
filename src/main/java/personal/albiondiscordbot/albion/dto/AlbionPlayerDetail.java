package personal.albiondiscordbot.albion.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from {@code /players/{id}} — the authoritative source for a character's
 * current guild.
 *
 * <p>Provides fame totals but <strong>no lifetime kill or death counts</strong>, which
 * is why counts have to come from battle participation instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlbionPlayerDetail(
        @JsonProperty("Id") String id,
        @JsonProperty("Name") String name,
        @JsonProperty("GuildId") String guildId,
        @JsonProperty("GuildName") String guildName,
        @JsonProperty("AllianceId") String allianceId,
        @JsonProperty("AllianceName") String allianceName,
        @JsonProperty("KillFame") Long killFame,
        @JsonProperty("DeathFame") Long deathFame,
        @JsonProperty("FameRatio") Double fameRatio,
        @JsonProperty("LifetimeStatistics") LifetimeStatistics lifetimeStatistics) {

    public long killFameOrZero() {
        return killFame == null ? 0L : killFame;
    }

    public long deathFameOrZero() {
        return deathFame == null ? 0L : deathFame;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifetimeStatistics(
            @JsonProperty("PvE") FameBucket pve,
            @JsonProperty("Gathering") GatheringFame gathering,
            @JsonProperty("Crafting") FameBucket crafting,
            @JsonProperty("FishingFame") Long fishingFame,
            @JsonProperty("FarmingFame") Long farmingFame) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GatheringFame(@JsonProperty("All") FameBucket all) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FameBucket(@JsonProperty("Total") Long total) {

        public long totalOrZero() {
            return total == null ? 0L : total;
        }
    }

    public long pveFame() {
        return lifetimeStatistics == null || lifetimeStatistics.pve() == null
                ? 0L
                : lifetimeStatistics.pve().totalOrZero();
    }

    public long gatheringFame() {
        return lifetimeStatistics == null
                        || lifetimeStatistics.gathering() == null
                        || lifetimeStatistics.gathering().all() == null
                ? 0L
                : lifetimeStatistics.gathering().all().totalOrZero();
    }

    public long craftingFame() {
        return lifetimeStatistics == null || lifetimeStatistics.crafting() == null
                ? 0L
                : lifetimeStatistics.crafting().totalOrZero();
    }

    public long fishingFame() {
        return lifetimeStatistics == null || lifetimeStatistics.fishingFame() == null
                ? 0L
                : lifetimeStatistics.fishingFame();
    }

    public long farmingFame() {
        return lifetimeStatistics == null || lifetimeStatistics.farmingFame() == null
                ? 0L
                : lifetimeStatistics.farmingFame();
    }
}
