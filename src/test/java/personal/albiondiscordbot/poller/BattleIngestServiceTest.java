package personal.albiondiscordbot.poller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.support.PostgresTestBase;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Proves the poller cannot double-count, which is what keeps {@code /stats} honest. */
@SpringBootTest
class BattleIngestServiceTest extends PostgresTestBase {

    @MockitoBean
    private JDA jda;

    @Autowired
    private BattleIngestService ingestService;

    @Autowired
    private JdbcClient jdbc;

    private AlbionBattle battle;
    private Set<String> trackedGuildIds;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM battle_participation").update();
        jdbc.sql("DELETE FROM killboard_post").update();
        jdbc.sql("DELETE FROM battle").update();

        ObjectMapper mapper = JsonMapper.builder().build();
        try (InputStream in = getClass().getResourceAsStream("/albion/battles-response.json")) {
            List<AlbionBattle> battles = List.of(mapper.readValue(in, AlbionBattle[].class));
            battle = battles.stream()
                    .max((a, b) -> Integer.compare(a.playerCount(), b.playerCount()))
                    .orElseThrow();
        }
        // Track whichever guild fielded the most players in the fixture.
        trackedGuildIds = Set.of(battle.players().values().stream()
                .filter(p -> p.guildId() != null && !p.guildId().isBlank())
                .collect(Collectors.groupingBy(AlbionBattle.Participant::guildId, Collectors.counting()))
                .entrySet()
                .stream()
                .max(java.util.Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey());
    }

    @Test
    @DisplayName("ingesting the same battle twice stores it once and does not double count")
    void ingestIsIdempotent() {
        boolean firstTime = ingestService.ingest(battle, trackedGuildIds);
        long battlesAfterFirst = count("battle");
        long participantsAfterFirst = count("battle_participation");
        Totals totalsAfterFirst = totals();

        boolean secondTime = ingestService.ingest(battle, trackedGuildIds);

        assertThat(firstTime).as("first sighting").isTrue();
        assertThat(secondTime).as("re-scan is not a first sighting").isFalse();

        assertThat(count("battle")).isEqualTo(battlesAfterFirst).isEqualTo(1);
        assertThat(count("battle_participation")).isEqualTo(participantsAfterFirst);
        // The regression that matters: stats must be byte-identical after a re-scan.
        assertThat(totals()).isEqualTo(totalsAfterFirst);
    }

    @Test
    @DisplayName("ingesting three times still yields the same totals")
    void repeatedIngestStaysStable() {
        ingestService.ingest(battle, trackedGuildIds);
        Totals expected = totals();

        ingestService.ingest(battle, trackedGuildIds);
        ingestService.ingest(battle, trackedGuildIds);

        assertThat(totals()).isEqualTo(expected);
    }

    @Test
    @DisplayName("only members of tracked guilds are stored")
    void storesOnlyTrackedGuildMembers() {
        ingestService.ingest(battle, trackedGuildIds);

        long expected = battle.players().values().stream()
                .filter(p -> p.guildId() != null && trackedGuildIds.contains(p.guildId()))
                .count();

        assertThat(count("battle_participation")).isEqualTo(expected);
        assertThat(expected).isLessThan(battle.playerCount());

        List<String> storedGuildIds = jdbc.sql("SELECT DISTINCT albion_guild_id FROM battle_participation")
                .query(String.class)
                .list();
        assertThat(storedGuildIds).containsExactlyInAnyOrderElementsOf(trackedGuildIds);
    }

    @Test
    @DisplayName("the battle's full player count is recorded even though only our members are stored")
    void recordsFullBattleSize() {
        ingestService.ingest(battle, trackedGuildIds);

        Integer playerCount = jdbc.sql("SELECT player_count FROM battle WHERE albion_battle_id = :id")
                .param("id", battle.id())
                .query(Integer.class)
                .single();

        // This is what the configurable CTA threshold is compared against, so it must
        // be the whole battle, not just our side.
        assertThat(playerCount).isEqualTo(battle.playerCount());
    }

    @Test
    @DisplayName("our own turnout is stored separately from the total battle size")
    void recordsGuildTurnoutSeparately() {
        ingestService.ingest(battle, trackedGuildIds);

        Integer guildCount = jdbc.sql(
                        "SELECT DISTINCT guild_player_count FROM battle_participation WHERE albion_battle_id = :id")
                .param("id", battle.id())
                .query(Integer.class)
                .single();

        long expected = battle.players().values().stream()
                .filter(p -> p.guildId() != null && trackedGuildIds.contains(p.guildId()))
                .count();

        assertThat(guildCount).isEqualTo((int) expected);
        // The whole point: this is smaller than the battle, because the battle includes
        // the enemy. Comparing the CTA threshold against the total counted them too.
        assertThat(guildCount).isLessThan(battle.playerCount());
    }

    @Test
    @DisplayName("a small party in someone else's big fight is not a CTA")
    void smallPartyInBigFightIsNotACta() {
        ingestService.ingest(battle, trackedGuildIds);

        int ourTurnout = jdbc.sql(
                        "SELECT DISTINCT guild_player_count FROM battle_participation WHERE albion_battle_id = :id")
                .param("id", battle.id())
                .query(Integer.class)
                .single();

        // A threshold just above our turnout must exclude the battle even though the
        // fight itself is far larger than that threshold.
        int threshold = ourTurnout + 1;
        assertThat(battle.playerCount()).isGreaterThan(threshold);

        Long ctas = jdbc.sql(
                        """
                        SELECT count(*) FROM battle_participation
                        WHERE albion_battle_id = :id AND guild_player_count >= :threshold
                        """)
                .param("id", battle.id())
                .param("threshold", threshold)
                .query(Long.class)
                .single();

        assertThat(ctas).isZero();
    }

    @Test
    @DisplayName("tracked participants are detectable for the killboard filter")
    void detectsTrackedParticipants() {
        ingestService.ingest(battle, trackedGuildIds);

        assertThat(ingestService.hasTrackedParticipants(battle.id())).isTrue();
        assertThat(ingestService.hasTrackedParticipants(-1L)).isFalse();
    }

    @Test
    @DisplayName("a battle with no tracked participants stores the battle but no rows")
    void noTrackedParticipants() {
        boolean stored = ingestService.ingest(battle, Set.of("SOME_OTHER_GUILD_ID"));

        assertThat(stored).isTrue();
        assertThat(count("battle")).isEqualTo(1);
        assertThat(count("battle_participation")).isZero();
        assertThat(ingestService.hasTrackedParticipants(battle.id())).isFalse();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private Totals totals() {
        return jdbc.sql(
                        """
                        SELECT COALESCE(SUM(kills),0), COALESCE(SUM(deaths),0), COALESCE(SUM(kill_fame),0)
                        FROM battle_participation
                        """)
                .query((rs, n) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
    }

    private record Totals(long kills, long deaths, long killFame) {
    }
}
