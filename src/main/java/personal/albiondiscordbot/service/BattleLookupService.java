package personal.albiondiscordbot.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Battle;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.poller.BattleIngestService;
import personal.albiondiscordbot.repository.BattleRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;

/**
 * Turns a battle id an officer typed into a battle the bot can pay out on, fetching it
 * from the API if the poller never stored it.
 *
 * <h2>Why the fetch exists</h2>
 *
 * <p>The poller works from {@code /battles}, which only reaches back 24 hours, and it
 * only stores battles a tracked guild fought in <em>at the time it looked</em>. So
 * {@code /split-cta} used to refuse a perfectly real fight whenever any of these was true:
 *
 * <ul>
 *   <li>the bot was down, or restarted, across the fight;
 *   <li>the guild was added with {@code /guild add} after the fight;
 *   <li>the officer got round to splitting more than a day later;
 *   <li>the fight was one of the smaller killboards of a CTA that the poller had already
 *       paged past.
 * </ul>
 *
 * <p>All four looked identical to the officer — "battle is not tracked" — and none of them
 * had anything to do with whether the guild was in the fight. Asking the API directly
 * settles the real question, and ingesting the answer means {@code /stats} picks the
 * battle up too.
 */
@Service
public class BattleLookupService {

    private static final Logger log = LoggerFactory.getLogger(BattleLookupService.class);

    private final BattleRepository battles;
    private final BattleIngestService ingestService;
    private final AlbionApiClient albion;
    private final TrackedAlbionGuildRepository trackedGuilds;
    private final AlbionProperties properties;

    public BattleLookupService(
            BattleRepository battles,
            BattleIngestService ingestService,
            AlbionApiClient albion,
            TrackedAlbionGuildRepository trackedGuilds,
            AlbionProperties properties) {
        this.battles = battles;
        this.ingestService = ingestService;
        this.albion = albion;
        this.trackedGuilds = trackedGuilds;
        this.properties = properties;
    }

    /** The most recent CTA-sized fight this server's guilds were in. */
    public Optional<Battle> latestCta(long discordGuildId, int minTotalPlayers, int minGuildPlayers) {
        return battles.findLatestCta(discordGuildId, minTotalPlayers, minGuildPlayers);
    }

    /**
     * The battle behind an id, backfilled from the API when the poller never saw it.
     *
     * @throws CommandException with a message naming which of the three possible reasons
     *     applies, if the id cannot be turned into a payable battle
     */
    public Battle require(long discordGuildId, long albionBattleId) {
        Optional<Battle> stored = battles.findByAlbionBattleId(albionBattleId);
        if (stored.isPresent()) {
            return stored.get();
        }
        return backfill(discordGuildId, albionBattleId);
    }

    private Battle backfill(long discordGuildId, long albionBattleId) {
        Set<String> ourGuildIds = ourGuildIds(discordGuildId);
        if (ourGuildIds.isEmpty()) {
            throw new CommandException(
                    "This server tracks no Albion guilds yet, so there is nothing to credit. "
                            + "Add one with `/guild add <name>` first.");
        }

        AlbionBattle battle = albion.getBattle(albionBattleId)
                .orElseThrow(() -> new CommandException(
                        ("Battle `%d` does not exist on the region this bot reads. Check the id — it is the "
                                        + "number in the killboard link, e.g. `417352406` in "
                                        + "`albionbb.com/battles/417352406`.")
                                .formatted(albionBattleId)));

        // Asked of the battle's guild list, exactly as the poller asks it, so a fight is
        // never "ours" here and not there.
        boolean oursFought = battle.guilds().keySet().stream().anyMatch(ourGuildIds::contains);
        if (!oursFought) {
            throw new CommandException(
                    ("None of your tracked guilds fought in battle `%d`, so there is nobody in it to credit. "
                                    + "If that is wrong, the guild may not be tracked yet — check `/guild list`.")
                            .formatted(albionBattleId));
        }

        // A battle keeps accruing participants for ~180 seconds after its last kill. Paying
        // out on a partial roster is not something /undo makes painless, so refuse rather
        // than credit half the people who were there.
        if (!battle.isClosed(Instant.now(), properties.poller().finalizeGrace())) {
            throw new CommandException(
                    ("Battle `%d` is still counting kills — Albion keeps a fight open for about three "
                                    + "minutes after the last one. Give it a moment, or the split would miss "
                                    + "whoever is still fighting.")
                            .formatted(albionBattleId));
        }

        // Participation rows are global game data, so they are stored for every tracked
        // guild on every Discord server, not just the one asking — the same rule the
        // poller follows. No killboard post is sent: this is an officer backfilling an old
        // fight on purpose, and a post about it now would only confuse the channel.
        ingestService.ingest(battle, Set.copyOf(trackedGuilds.findAllTrackedAlbionGuildIds()));
        log.info(
                "Backfilled battle {} ({} players) on demand for Discord guild {}",
                albionBattleId,
                battle.playerCount(),
                discordGuildId);

        return battles.findByAlbionBattleId(albionBattleId)
                .orElseThrow(() -> new IllegalStateException(
                        "Battle " + albionBattleId + " was ingested but cannot be read back"));
    }

    private Set<String> ourGuildIds(long discordGuildId) {
        List<TrackedAlbionGuild> tracked = trackedGuilds.findByDiscordGuildId(discordGuildId);
        return tracked.stream().map(TrackedAlbionGuild::getAlbionGuildId).collect(Collectors.toSet());
    }
}
