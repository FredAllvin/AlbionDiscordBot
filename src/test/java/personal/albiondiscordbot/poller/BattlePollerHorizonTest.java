package personal.albiondiscordbot.poller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.domain.PollerState;

/**
 * How deep each poll digs.
 *
 * <p>This is the difference between "the bot caught up after a restart" and "those CTAs
 * are gone forever", so the boundaries are worth pinning down. No Spring context needed —
 * the calculation is pure.
 */
class BattlePollerHorizonTest {

    private static final AlbionProperties.Poller CONFIG = new AlbionProperties.Poller(
            true,
            Duration.ofMinutes(2),
            Duration.ofMinutes(20), // overlap
            Duration.ofHours(6), // cold start
            Duration.ofSeconds(200),
            200);

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private PollerState stateLastSucceededAt(Instant lastSuccess) throws Exception {
        Constructor<PollerState> constructor = PollerState.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        PollerState state = constructor.newInstance();

        var field = PollerState.class.getDeclaredField("lastSuccessAt");
        field.setAccessible(true);
        field.set(state, lastSuccess);
        return state;
    }

    @Test
    @DisplayName("a routine poll only re-scans the overlap, not hours of history")
    void routinePollIsShallow() throws Exception {
        PollerState state = stateLastSucceededAt(NOW.minus(Duration.ofMinutes(2)));

        Instant horizon = BattlePoller.horizonFor(state, CONFIG, NOW);

        // 2 minutes since the last success + 20 minutes overlap = 22 minutes back.
        // Battles are dense enough that a fixed 6h window would cost ~34 requests here.
        assertThat(horizon).isEqualTo(NOW.minus(Duration.ofMinutes(22)));
    }

    @Test
    @DisplayName("after downtime the horizon stretches to cover the whole gap")
    void downtimeStretchesTheHorizon() throws Exception {
        PollerState state = stateLastSucceededAt(NOW.minus(Duration.ofHours(8)));

        Instant horizon = BattlePoller.horizonFor(state, CONFIG, NOW);

        assertThat(horizon).isEqualTo(NOW.minus(Duration.ofHours(8)).minus(Duration.ofMinutes(20)));
    }

    @Test
    @DisplayName("the horizon never claims to see past what range=day retains")
    void clampedToApiRetention() throws Exception {
        PollerState state = stateLastSucceededAt(NOW.minus(Duration.ofDays(5)));

        Instant horizon = BattlePoller.horizonFor(state, CONFIG, NOW);

        // Anything older is unrecoverable, so there is no point paging for it.
        assertThat(horizon).isEqualTo(NOW.minus(BattlePoller.API_RETENTION));
    }

    @Test
    @DisplayName("a first run with no recorded success uses the cold-start reach")
    void coldStart() throws Exception {
        assertThat(BattlePoller.horizonFor(null, CONFIG, NOW))
                .isEqualTo(NOW.minus(Duration.ofHours(6)).minus(Duration.ofMinutes(20)));

        assertThat(BattlePoller.horizonFor(stateLastSucceededAt(null), CONFIG, NOW))
                .isEqualTo(NOW.minus(Duration.ofHours(6)).minus(Duration.ofMinutes(20)));
    }

    @Test
    @DisplayName("the overlap always covers the finalize grace period")
    void overlapCoversFinalizeGrace() {
        // Battles younger than finalizeGrace are skipped, so the next poll has to reach
        // back past that or they would never be picked up at all.
        assertThat(CONFIG.overlap()).isGreaterThan(CONFIG.finalizeGrace());
        assertThat(CONFIG.overlap()).isGreaterThan(CONFIG.interval());
    }
}
