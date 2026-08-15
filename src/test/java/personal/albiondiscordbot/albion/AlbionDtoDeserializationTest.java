package personal.albiondiscordbot.albion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.albion.dto.AlbionGuildDetail;
import personal.albiondiscordbot.albion.dto.AlbionPlayerDetail;
import personal.albiondiscordbot.albion.dto.AlbionSearchResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses real captured Albion API responses.
 *
 * <p>Fixtures are genuine payloads rather than hand-written JSON, which is what makes
 * this test able to catch the API's inconsistent casing — {@code KillFame} on players
 * but {@code killFame} on guilds — and its nanosecond-precision timestamps.
 */
class AlbionDtoDeserializationTest {

    // Jackson 3 supports java.time out of the box; no JavaTimeModule to register.
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private <T> T read(String fixture, Class<T> type) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/albion/" + fixture)) {
            assertThat(in).as("fixture %s must exist", fixture).isNotNull();
            return mapper.readValue(in, type);
        }
    }

    private List<AlbionBattle> readBattles() throws Exception {
        return List.of(read("battles-response.json", AlbionBattle[].class));
    }

    @Test
    @DisplayName("search response parses players and guilds")
    void parsesSearchResponse() throws Exception {
        AlbionSearchResponse response = read("search-response.json", AlbionSearchResponse.class);

        assertThat(response.players()).isNotEmpty();
        AlbionSearchResponse.PlayerHit player = response.players().get(0);
        assertThat(player.id()).isNotBlank();
        assertThat(player.name()).isNotBlank();
    }

    @Test
    @DisplayName("player detail parses PascalCase fame fields")
    void parsesPlayerDetail() throws Exception {
        AlbionPlayerDetail player = read("player-detail.json", AlbionPlayerDetail.class);

        assertThat(player.id()).isNotBlank();
        assertThat(player.name()).isNotBlank();
        // If the @JsonProperty("KillFame") mapping were wrong these would be zero.
        assertThat(player.killFameOrZero()).isPositive();
        assertThat(player.deathFameOrZero()).isPositive();
        assertThat(player.pveFame()).isNotNegative();
        assertThat(player.gatheringFame()).isNotNegative();
        assertThat(player.craftingFame()).isNotNegative();
    }

    @Test
    @DisplayName("guild detail parses the lower-cased killFame field")
    void parsesGuildDetail() throws Exception {
        AlbionGuildDetail guild = read("guild-detail.json", AlbionGuildDetail.class);

        assertThat(guild.id()).isNotBlank();
        assertThat(guild.name()).isNotBlank();
        // This is the trap: guilds use "killFame", players use "KillFame".
        assertThat(guild.killFame()).isNotNull();
        assertThat(guild.memberCount()).isNotNull();
    }

    @Test
    @DisplayName("battles parse fully, including the complete player map")
    void parsesBattles() throws Exception {
        List<AlbionBattle> battles = readBattles();

        assertThat(battles).isNotEmpty();

        AlbionBattle biggest = battles.stream()
                .max((a, b) -> Integer.compare(a.playerCount(), b.playerCount()))
                .orElseThrow();

        assertThat(biggest.id()).isPositive();
        assertThat(biggest.startTime()).isNotNull();
        assertThat(biggest.timeout()).isNotNull();
        assertThat(biggest.playerCount()).isGreaterThan(1);
        assertThat(biggest.guilds()).isNotEmpty();

        AlbionBattle.Participant participant = biggest.players().values().iterator().next();
        assertThat(participant.id()).isNotBlank();
        assertThat(participant.name()).isNotBlank();
        assertThat(participant.kills()).isNotNegative();
        assertThat(participant.deaths()).isNotNegative();
    }

    @Test
    @DisplayName("clusterName is null, so nothing may depend on a zone name")
    void clusterNameIsAlwaysNull() throws Exception {
        List<AlbionBattle> battles = readBattles();

        assertThat(battles).allSatisfy(b -> assertThat(b.clusterName()).isNull());
    }

    @Test
    @DisplayName("a battle is only closed once its timeout has passed")
    void closedDependsOnTimeout() throws Exception {
        List<AlbionBattle> battles = readBattles();

        AlbionBattle battle = battles.get(0);

        java.time.Duration grace = java.time.Duration.ofSeconds(200);

        assertThat(battle.isClosed(battle.timeout().minusSeconds(1), grace)).isFalse();
        assertThat(battle.isClosed(battle.timeout().plusSeconds(1), grace)).isTrue();
        assertThat(battle.isClosed(Instant.now(), grace)).isTrue();
    }
}
