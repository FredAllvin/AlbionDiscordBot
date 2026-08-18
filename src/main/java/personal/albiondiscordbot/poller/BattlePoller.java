package personal.albiondiscordbot.poller;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.domain.PollerState;
import personal.albiondiscordbot.repository.PollerStateRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.service.KillboardService;

/**
 * Polls recent battles and stores the ones our guilds fought in.
 *
 * <p>Correctness does not come from the watermark. It comes from the database: a battle
 * is keyed by its Albion id and participation by (battle, player), so re-scanning an
 * overlapping window costs nothing and cannot double count. The watermark only decides
 * <em>how deep to page</em>, which makes a missed cycle self-healing rather than a
 * permanent hole.
 *
 * <p>Battles younger than the finalize grace period are skipped rather than stored:
 * a battle keeps accruing participants for roughly 180 seconds after its last kill, and
 * ingesting early would record a partial roster and post a wrong killboard embed.
 *
 * <p>Deferring a battle is only safe if the next run can still reach it, and the overlap
 * alone does not guarantee that: the watermark advances on every run, including the ones
 * that skipped an unfinalized battle, so a battle has to finalize before the overlap
 * slides past its <em>start</em> time. Big fights lose that race, because a battle stays
 * open until 180s after its last kill and the big ones are the ones that keep producing
 * kills. Measured on the live EU list, the median 100-player battle is open for 23.6
 * minutes — longer than the 22 minutes a routine poll reaches back. So each run records
 * the oldest battle it deferred, and the next one pages back to that battle explicitly.
 */
@Component
@ConditionalOnProperty(value = "albion.poller.enabled", havingValue = "true", matchIfMissing = true)
public class BattlePoller {

    private static final Logger log = LoggerFactory.getLogger(BattlePoller.class);

    /** {@code range=day} reaches back this far and no further. */
    static final java.time.Duration API_RETENTION = java.time.Duration.ofHours(24);

    private final AlbionApiClient albion;
    private final BattleIngestService ingestService;
    private final KillboardService killboardService;
    private final TrackedAlbionGuildRepository trackedGuilds;
    private final PollerStateRepository pollerStates;
    private final AlbionProperties properties;

    /** Single-flight guard; fixedDelay already prevents overlap on one instance. */
    private final AtomicBoolean running = new AtomicBoolean();

    public BattlePoller(
            AlbionApiClient albion,
            BattleIngestService ingestService,
            KillboardService killboardService,
            TrackedAlbionGuildRepository trackedGuilds,
            PollerStateRepository pollerStates,
            AlbionProperties properties) {
        this.albion = albion;
        this.ingestService = ingestService;
        this.killboardService = killboardService;
        this.trackedGuilds = trackedGuilds;
        this.pollerStates = pollerStates;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${albion.poller.interval:PT2M}",
            initialDelayString = "${albion.poller.initial-delay:PT30S}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Battle poll failed", e);
            pollerStates.findById(PollerState.BATTLES).ifPresent(state -> {
                state.recordFailure();
                pollerStates.save(state);
            });
        } finally {
            running.set(false);
        }
    }

    void runOnce() {
        Set<String> trackedGuildIds = new HashSet<>(trackedGuilds.findAllTrackedAlbionGuildIds());
        if (trackedGuildIds.isEmpty()) {
            // Nothing to attribute battles to yet; skip rather than crawl the API.
            return;
        }

        PollerState state = pollerStates.findById(PollerState.BATTLES).orElse(null);
        AlbionProperties.Poller config = properties.poller();

        Instant now = Instant.now();
        Instant horizon = horizonFor(state, config, now);

        int ingested = 0;
        int pagesRead = 0;

        // The oldest battle this run defers. Recomputed from scratch rather than merged
        // with what was stored, so it clears itself the moment nothing is outstanding.
        Instant oldestOpen = null;

        for (int page = 0; page < config.maxPages(); page++) {
            List<AlbionBattle> batch =
                    albion.getBattles("day", AlbionApiClient.MAX_BATTLE_PAGE_SIZE, page * AlbionApiClient.MAX_BATTLE_PAGE_SIZE);
            pagesRead++;

            if (batch.isEmpty()) {
                break;
            }

            Instant oldestInPage = now;
            for (AlbionBattle battle : batch) {
                if (battle.startTime() != null && battle.startTime().isBefore(oldestInPage)) {
                    oldestInPage = battle.startTime();
                }
                if (!involvesTrackedGuild(battle, trackedGuildIds)) {
                    continue;
                }
                // Still accruing participants — revisit on a later run. Asks the battle's
                // own `timeout` field rather than re-deriving "closed" from endTime here;
                // two competing definitions of finalized in one codebase is how they drift
                // apart. finalizeGrace still applies when the API omits a timeout.
                //
                // Note what is being remembered: the battle's START, not now. That is the
                // depth the next run has to page to in order to see this battle again,
                // and for a long fight it is much deeper than the overlap would reach.
                if (!battle.isClosed(now, config.finalizeGrace())) {
                    if (battle.startTime() != null
                            && (oldestOpen == null || battle.startTime().isBefore(oldestOpen))) {
                        oldestOpen = battle.startTime();
                    }
                    continue;
                }

                if (ingestService.ingest(battle, trackedGuildIds)) {
                    ingested++;
                }
                // Offered on every sighting, not only the first. Discord can refuse a
                // killboard — a missing permission, a rate limit, a restart mid-send — and
                // handing the battle over again is the only way that post is ever retried.
                // Keeping it to one post is killboard_post's job, not this loop's.
                killboardService.onBattleFinalized(battle);
            }

            if (oldestInPage.isBefore(horizon)) {
                break;
            }
        }

        if (state != null) {
            state.recordSuccess();
            // Written even when null: that is how a finalized battle releases the floor.
            state.setOldestOpenBattleAt(oldestOpen);
            if (ingested > 0) {
                state.markFirstIngestIfAbsent();
            }
            pollerStates.save(state);
        }

        if (ingested > 0) {
            log.info("Ingested {} new battle(s) from {} page(s)", ingested, pagesRead);
        }
    }

    /**
     * How far back this run should page.
     *
     * <p>Anchored on the last successful poll rather than a fixed window: a routine poll
     * a minute after the last one only has to re-check the overlap, while the first
     * poll after an outage automatically reaches back across the whole gap.
     *
     * <p>Then extended to cover any battle the previous run deferred as unfinalized. The
     * overlap is a fixed guess and a battle's duration is not, so without this the reach
     * is only accidentally sufficient — and it runs out first for the longest battles,
     * which are the ones worth posting.
     *
     * <p>Clamped to 24 hours because {@code range=day} cannot see further. Anything
     * older than that is unrecoverable — which is the real cost of extended downtime.
     */
    static Instant horizonFor(PollerState state, AlbionProperties.Poller config, Instant now) {
        Instant anchor = state == null || state.getLastSuccessAt() == null
                ? now.minus(config.coldStartLookback())
                : state.getLastSuccessAt();

        Instant horizon = anchor.minus(config.overlap());

        // The break in runOnce() fires after the page is processed, so a horizon equal to
        // the battle's start still reads the page holding it.
        Instant deferred = state == null ? null : state.getOldestOpenBattleAt();
        if (deferred != null && deferred.isBefore(horizon)) {
            horizon = deferred;
        }

        Instant apiLimit = now.minus(API_RETENTION);

        return horizon.isBefore(apiLimit) ? apiLimit : horizon;
    }

    private boolean involvesTrackedGuild(AlbionBattle battle, Set<String> trackedGuildIds) {
        return battle.guilds().keySet().stream().anyMatch(trackedGuildIds::contains);
    }
}
