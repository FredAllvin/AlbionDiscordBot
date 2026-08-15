package personal.albiondiscordbot.albion;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.albion.dto.AlbionGuildDetail;
import personal.albiondiscordbot.albion.dto.AlbionPlayerDetail;
import personal.albiondiscordbot.albion.dto.AlbionSearchResponse;
import personal.albiondiscordbot.config.AlbionProperties;

/**
 * Client for the Albion Online game-info API (EU region by default).
 *
 * <p>Every request goes through a single-permit rate limiter with a minimum spacing, so
 * an interactive {@code /register} burst cannot collide with the battle poller and get
 * the whole bot rate-limited. Cloudflare fronts this API and is unforgiving of bursts.
 *
 * <h2>Why every request carries a throwaway query parameter</h2>
 *
 * <p>This API answers from a shared cache that it advertises as {@code max-age=600} and
 * then serves well past. Measured against the live EU host on 15 August 2026:
 *
 * <ul>
 *   <li>{@code /players/{id}} and {@code /search} come back with an {@code Age} header of
 *       anywhere from 2 to 160 seconds on ordinary reads, so a character's guild can be
 *       reported as it stood ten minutes ago. This is what made {@code /register} tell
 *       someone they were still in their old guild, then accept them seconds later —
 *       consecutive requests land on different cache vintages.
 *   <li>{@code /battles} advertises {@code max-age=300} but was observed serving
 *       {@code Age: 28954} (eight hours). At {@code offset=153} the cached page's oldest
 *       battle was {@code 2026-08-14T23:15:41Z} while an uncached read of the identical
 *       URL returned {@code 2026-08-15T02:11:54Z} — the stale page claims to reach almost
 *       three hours further back than it does. {@code BattlePoller} decides when to stop
 *       paging from exactly that value, so a stale page makes it stop early and then
 *       advance its watermark, losing every battle in the gap permanently.
 * </ul>
 *
 * <p>A unique query parameter is the only thing that was found to work. A
 * {@code Cache-Control: no-cache} <em>request</em> header changes nothing — the same
 * {@code Age: 157} response comes back. The parameter is ignored by the API and moves
 * responses from ~18 ms (cache) to ~200 ms (origin), which is the confirmation that it
 * reached the real thing.
 *
 * <p>Set {@code albion.api.bypass-cache=false} to turn this off, which is only worth
 * doing if the API ever starts objecting to the extra origin traffic. The bot makes a
 * few requests a minute, so it does not today.
 */
@Component
public class AlbionApiClient {

    private static final Logger log = LoggerFactory.getLogger(AlbionApiClient.class);

    /** The API caps this; asking for more returns HTTP 400. */
    public static final int MAX_BATTLE_PAGE_SIZE = 51;

    /** Meaningless to the API; its only job is to miss the cache. */
    private static final String CACHE_BUSTER_PARAM = "_";

    private final RestClient restClient;
    private final AlbionRateLimiter rateLimiter;
    private final boolean bypassCache;

    /**
     * Random per JVM so a restart cannot reissue a value that is still sitting in the
     * cache from the previous run, which would defeat the whole point.
     */
    private final String cacheBusterPrefix =
            Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);

    private final AtomicLong cacheBusterCounter = new AtomicLong();

    public AlbionApiClient(RestClient.Builder builder, AlbionProperties properties) {
        AlbionProperties.Api api = properties.api();
        this.restClient = builder.baseUrl(api.baseUrl())
                // Mandatory: Cloudflare answers default Java user agents with 403.
                .defaultHeader("User-Agent", api.userAgent())
                .defaultHeader("Accept", "application/json")
                .build();
        this.rateLimiter = new AlbionRateLimiter(api.minRequestInterval());
        this.bypassCache = api.bypassCache();
    }

    /** Adds the cache-missing parameter, unless it has been switched off. */
    private UriBuilder uncached(UriBuilder builder) {
        if (!bypassCache) {
            return builder;
        }
        return builder.queryParam(
                CACHE_BUSTER_PARAM,
                cacheBusterPrefix + Long.toUnsignedString(cacheBusterCounter.incrementAndGet(), 36));
    }

    /**
     * Every character whose name matches exactly, case-insensitively.
     *
     * <p>The search endpoint matches substrings, so a query for {@code Bob} also returns
     * {@code Bobby} and {@code BobX}. Registering a name the member did not type would be
     * worse than failing, so only an exact match counts.
     *
     * <p>Returns a <strong>list</strong> because Albion treats names differing only by
     * case as different characters, and both can exist at once. {@code 300pingenjoyer}
     * and {@code 300PingEnjoyer} are two real accounts; only the second is in Dumbo
     * Elephants, and search lists the guildless one first. Picking one and hoping meant
     * {@code /register} reported the guild of a character the member does not play, and
     * {@code /force-register} would have linked them to it — which attendance keys on, so
     * {@code /split-cta} would never have paid them.
     *
     * <p>A case-sensitive match of what was typed sorts first, so the likeliest candidate
     * leads for callers that only want one.
     */
    public List<AlbionSearchResponse.PlayerHit> findPlayersByExactName(String name) {
        String typed = name.trim();
        String wanted = typed.toLowerCase(Locale.ROOT);

        return search(typed).players().stream()
                .filter(p -> p.name() != null && p.name().toLowerCase(Locale.ROOT).equals(wanted))
                .sorted(Comparator.comparing(p -> !p.name().equals(typed)))
                .toList();
    }

    /**
     * The single best exact-name match, for callers with no way to disambiguate.
     *
     * <p>Prefer {@link #findPlayersByExactName} anywhere the right answer depends on the
     * character's guild — see the note there about same-name accounts.
     */
    public Optional<AlbionSearchResponse.PlayerHit> findPlayerByExactName(String name) {
        return findPlayersByExactName(name).stream().findFirst();
    }

    /** Finds a guild by exact name, case-insensitively. */
    public Optional<AlbionSearchResponse.GuildHit> findGuildByExactName(String name) {
        AlbionSearchResponse response = search(name);
        String wanted = name.trim().toLowerCase(Locale.ROOT);

        return response.guilds().stream()
                .filter(g -> g.name() != null && g.name().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    public AlbionSearchResponse search(String query) {
        return rateLimiter.call(() -> restClient
                .get()
                .uri(uri -> uncached(uri.path("/search").queryParam("q", query)).build())
                .retrieve()
                .body(AlbionSearchResponse.class));
    }

    /**
     * A character's current state, including its guild.
     *
     * <p>Fresher than {@code /search}, but only because the cache is bypassed — the two
     * endpoints are cached identically, so neither is inherently more current than the
     * other. Both were measured returning the same {@code max-age=600}.
     */
    public Optional<AlbionPlayerDetail> getPlayer(String albionPlayerId) {
        return rateLimiter.call(() -> {
            try {
                return Optional.ofNullable(restClient
                        .get()
                        .uri(uri -> uncached(uri.path("/players/{id}")).build(albionPlayerId))
                        .retrieve()
                        .body(AlbionPlayerDetail.class));
            } catch (RestClientException e) {
                if (isNotFound(e)) {
                    return Optional.empty();
                }
                throw e;
            }
        });
    }

    public Optional<AlbionGuildDetail> getGuild(String albionGuildId) {
        return rateLimiter.call(() -> {
            try {
                return Optional.ofNullable(restClient
                        .get()
                        .uri(uri -> uncached(uri.path("/guilds/{id}")).build(albionGuildId))
                        .retrieve()
                        .body(AlbionGuildDetail.class));
            } catch (RestClientException e) {
                if (isNotFound(e)) {
                    return Optional.empty();
                }
                throw e;
            }
        });
    }

    /**
     * One page of recent battles.
     *
     * @param range {@code day}, {@code week} or {@code month}
     * @param offset how far back to page; deep offsets work
     */
    public List<AlbionBattle> getBattles(String range, int limit, int offset) {
        int cappedLimit = Math.min(limit, MAX_BATTLE_PAGE_SIZE);
        return rateLimiter.call(() -> {
            List<AlbionBattle> battles = restClient
                    .get()
                    .uri(uri -> uncached(uri.path("/battles")
                                    .queryParam("range", range)
                                    .queryParam("limit", cappedLimit)
                                    .queryParam("offset", offset)
                                    .queryParam("sort", "recent"))
                            .build())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<AlbionBattle>>() {});
            return battles == null ? List.of() : battles;
        });
    }

    private boolean isNotFound(RestClientException e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException statusException) {
            HttpStatusCode status = statusException.getStatusCode();
            if (status.value() == 403) {
                // Almost always the User-Agent being rejected by Cloudflare rather than
                // a genuine authorisation problem, so make it loud instead of silent.
                log.error(
                        "Albion API returned 403. This usually means the configured User-Agent "
                                + "was rejected by Cloudflare — check albion.api.user-agent.");
            }
            return status.value() == 404;
        }
        return false;
    }

    /** Serialises requests and enforces a minimum gap between them. */
    static final class AlbionRateLimiter {

        private final long minIntervalMillis;
        private final Object lock = new Object();
        private long lastRequestAt;

        AlbionRateLimiter(Duration minInterval) {
            this.minIntervalMillis = minInterval == null ? 0L : minInterval.toMillis();
        }

        <T> T call(java.util.function.Supplier<T> request) {
            synchronized (lock) {
                long wait = minIntervalMillis - (System.currentTimeMillis() - lastRequestAt);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AlbionApiException("Interrupted while waiting to call the Albion API", e);
                    }
                }
                try {
                    return request.get();
                } finally {
                    lastRequestAt = System.currentTimeMillis();
                }
            }
        }
    }
}
