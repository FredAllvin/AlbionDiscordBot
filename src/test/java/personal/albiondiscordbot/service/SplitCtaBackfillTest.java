package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Battle;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.poller.BattleIngestService;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * "Battle is not tracked" was the bot admitting it had not been looking, and saying it in
 * a way that read as "your guild was not in that fight".
 *
 * <p>The poller works from {@code /battles}, which reaches back 24 hours, and it only
 * stores fights a tracked guild was in <em>when it looked</em>. So a real CTA came back
 * refused whenever the bot restarted across it, the guild was added afterwards, the
 * officer split it a day late, or the fight was one of the smaller killboards the poller
 * had already paged past. None of those are reasons not to pay people, and the API can
 * still answer for any of them: {@code /battles/{id}} has no 24-hour floor.
 */
@SpringBootTest
class SplitCtaBackfillTest extends PostgresTestBase {

    private static final long GUILD = 6060L;
    private static final long UNTRACKED_GUILD = 6161L;
    private static final String OUR_GUILD_ID = "d6HcQHoTSH-2qY1oYVNjEQ";
    private static final String SOMEONE_ELSE = "NVOVeQwVTt2wOT0rXvusxw";
    private static final long OLD_FIGHT = 417352406L;

    @MockitoBean
    private JDA jda;

    @MockitoBean
    private AlbionApiClient albion;

    @Autowired
    private BattleLookupService battleLookup;

    @Autowired
    private BattleIngestService ingestService;

    @Autowired
    private TrackedAlbionGuildRepository trackedGuilds;

    @Autowired
    private DiscordGuildConfigRepository configs;

    @Autowired
    private GuildConfigService guildConfigService;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM battle_participation").update();
        jdbc.sql("DELETE FROM killboard_post").update();
        jdbc.sql("DELETE FROM battle").update();
        trackedGuilds.deleteAll();
        configs.deleteAll();

        guildConfigService.getOrCreate(GUILD);
        guildConfigService.getOrCreate(UNTRACKED_GUILD);
        trackedGuilds.save(new TrackedAlbionGuild(GUILD, OUR_GUILD_ID, "Dumbo Elephants", 1L));
    }

    @Test
    @DisplayName("a fight the poller never saw is fetched and paid, not refused")
    void backfillsFromTheApi() {
        when(albion.getBattle(OLD_FIGHT)).thenReturn(Optional.of(battle(closed(), OUR_GUILD_ID)));

        Battle resolved = battleLookup.require(GUILD, OLD_FIGHT);

        assertThat(resolved.getAlbionBattleId()).isEqualTo(OLD_FIGHT);
        assertThat(resolved.getPlayerCount()).isEqualTo(3);

        // Ingested, not just read: attendance rows are what /split-cta and /stats pay and
        // count from, so a backfill has to leave the same trace the poller would.
        assertThat(count("battle_participation")).isEqualTo(3);
    }

    @Test
    @DisplayName("a fight already stored is not fetched again")
    void storedBattleSkipsTheApi() {
        ingestService.ingest(battle(closed(), OUR_GUILD_ID), Set.of(OUR_GUILD_ID));

        assertThat(battleLookup.require(GUILD, OLD_FIGHT).getAlbionBattleId()).isEqualTo(OLD_FIGHT);

        verify(albion, never()).getBattle(anyLong());
    }

    @Test
    @DisplayName("an id that is not a battle says so, instead of blaming the guild")
    void unknownIdIsNamedAsSuch() {
        when(albion.getBattle(OLD_FIGHT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> battleLookup.require(GUILD, OLD_FIGHT))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("417352406");

        assertThat(count("battle")).isZero();
    }

    @Test
    @DisplayName("a fight none of our guilds were in is still refused")
    void someoneElsesFightIsRefused() {
        when(albion.getBattle(OLD_FIGHT)).thenReturn(Optional.of(battle(closed(), SOMEONE_ELSE)));

        assertThatThrownBy(() -> battleLookup.require(GUILD, OLD_FIGHT))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("None of your tracked guilds");

        // Nothing is stored for a fight we were not in.
        assertThat(count("battle")).isZero();
    }

    @Test
    @DisplayName("a fight that is still going is refused rather than paid on a partial roster")
    void openBattleIsRefused() {
        // Albion keeps a battle open for ~180s after its last kill, and the roster grows
        // the whole time. Crediting now would miss whoever is still fighting.
        when(albion.getBattle(OLD_FIGHT))
                .thenReturn(Optional.of(battle(Instant.now().plusSeconds(120), OUR_GUILD_ID)));

        assertThatThrownBy(() -> battleLookup.require(GUILD, OLD_FIGHT))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("still counting kills");

        assertThat(count("battle")).isZero();
    }

    @Test
    @DisplayName("a server with no tracked guilds is told to add one, not that the id is wrong")
    void noTrackedGuildsIsItsOwnMessage() {
        assertThatThrownBy(() -> battleLookup.require(UNTRACKED_GUILD, OLD_FIGHT))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("/guild add");

        verify(albion, never()).getBattle(anyLong());
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant closed() {
        return Instant.parse("2026-08-14T10:21:33Z");
    }

    private static AlbionBattle battle(Instant timeout, String guildId) {
        Map<String, AlbionBattle.Participant> players = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String playerId = "p" + i;
            players.put(
                    playerId,
                    new AlbionBattle.Participant(
                            playerId, playerId, 2, 1, 500_000L, guildId, "Some Guild", "", ""));
        }
        return new AlbionBattle(
                OLD_FIGHT,
                Instant.parse("2026-08-14T10:18:21Z"),
                Instant.parse("2026-08-14T10:18:33Z"),
                timeout,
                498_971L,
                3,
                null,
                players,
                Map.of(guildId, new AlbionBattle.Side(guildId, "Some Guild", 3, 0, 498_971L, "", "")),
                Map.of());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
