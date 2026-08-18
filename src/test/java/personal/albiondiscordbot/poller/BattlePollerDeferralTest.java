package personal.albiondiscordbot.poller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.domain.PollerState;
import personal.albiondiscordbot.repository.PollerStateRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.service.KillboardService;

/**
 * What the poller owes itself after a run that deferred a battle.
 *
 * <p>The skip this covers is not hypothetical: battle 417916301 (91 players, Dumbo
 * Elephants, 15 August 2026) stayed open 18.6 minutes, and by the time it finalized the
 * watermark had walked to within ~2 minutes of its start time. Two shorter battles in
 * the same window posted; that one never did.
 */
class BattlePollerDeferralTest {

    private static final String OUR_GUILD = "d6HcQHoTSH-2qY1oYVNjEQ";

    /** {@code runOnce} reads the wall clock, so the fixture has to be anchored to it. */
    private Instant now;

    private AlbionApiClient albion;
    private BattleIngestService ingestService;
    private KillboardService killboardService;
    private PollerStateRepository pollerStates;
    private PollerState state;
    private BattlePoller poller;

    @BeforeEach
    void setUp() throws Exception {
        now = Instant.now();
        albion = mock(AlbionApiClient.class);
        ingestService = mock(BattleIngestService.class);
        killboardService = mock(KillboardService.class);
        pollerStates = mock(PollerStateRepository.class);

        TrackedAlbionGuildRepository trackedGuilds = mock(TrackedAlbionGuildRepository.class);
        when(trackedGuilds.findAllTrackedAlbionGuildIds()).thenReturn(List.of(OUR_GUILD));

        Constructor<PollerState> constructor = PollerState.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        state = constructor.newInstance();
        when(pollerStates.findById(PollerState.BATTLES)).thenReturn(Optional.of(state));

        when(ingestService.ingest(any(), any())).thenReturn(true);

        AlbionProperties properties = new AlbionProperties(
                new AlbionProperties.Api(
                        "http://localhost", "test", Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), true),
                new AlbionProperties.Poller(
                        true,
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(20),
                        Duration.ofHours(6),
                        Duration.ofSeconds(200),
                        200),
                "https://europe.albionbb.com/battles/%d");

        poller = new BattlePoller(albion, ingestService, killboardService, trackedGuilds, pollerStates, properties);
    }

    /** One page of results, then nothing, which is enough to end the paging loop. */
    private void serve(AlbionBattle... battles) {
        when(albion.getBattles(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(albion.getBattles(eq("day"), anyInt(), eq(0))).thenReturn(List.of(battles));
    }

    private static AlbionBattle battle(long id, String guildId, Instant start, Instant timeout) {
        return new AlbionBattle(
                id,
                start,
                timeout.minus(Duration.ofMinutes(3)),
                timeout,
                14253634L,
                60,
                null,
                Map.of(
                        "p1",
                        new AlbionBattle.Participant("p1", "Someone", 43, 3, 14253634L, guildId, "Guild", null, null)),
                Map.of(guildId, new AlbionBattle.Side(guildId, "Guild", 43, 3, 14253634L, null, null)),
                Map.of());
    }

    @Test
    @DisplayName("a battle still accruing kills is remembered by its start time")
    void openBattleIsRemembered() {
        Instant startedAt = now.minus(Duration.ofMinutes(18));
        serve(battle(417916301L, OUR_GUILD, startedAt, now.plus(Duration.ofSeconds(30))));

        poller.runOnce();

        verify(ingestService, never()).ingest(any(), any());
        verify(killboardService, never()).onBattleFinalized(any());
        assertThat(state.getOldestOpenBattleAt())
                .as("the next run has to page back this far, not just one overlap")
                .isEqualTo(startedAt);
    }

    @Test
    @DisplayName("finalizing the battle releases the floor")
    void closedBattleClearsTheFloor() {
        Instant startedAt = now.minus(Duration.ofMinutes(18));
        state.setOldestOpenBattleAt(startedAt);
        serve(battle(417916301L, OUR_GUILD, startedAt, now.minus(Duration.ofSeconds(30))));

        poller.runOnce();

        verify(killboardService).onBattleFinalized(any());
        assertThat(state.getOldestOpenBattleAt())
                .as("nothing outstanding, so the poll goes back to being shallow")
                .isNull();
    }

    @Test
    @DisplayName("someone else's long siege does not drag our paging depth")
    void openBattleOfAnotherGuildIsIgnored() {
        serve(battle(
                417925846L, "someoneElsesGuildId", now.minus(Duration.ofHours(2)), now.plus(Duration.ofMinutes(1))));

        poller.runOnce();

        assertThat(state.getOldestOpenBattleAt()).isNull();
    }

    @Test
    @DisplayName("the oldest open battle wins when several are outstanding")
    void oldestOpenBattleWins() {
        Instant older = now.minus(Duration.ofMinutes(23));
        serve(
                battle(1L, OUR_GUILD, now.minus(Duration.ofMinutes(4)), now.plus(Duration.ofSeconds(30))),
                battle(2L, OUR_GUILD, older, now.plus(Duration.ofSeconds(30))));

        poller.runOnce();

        assertThat(state.getOldestOpenBattleAt()).isEqualTo(older);
    }
}
