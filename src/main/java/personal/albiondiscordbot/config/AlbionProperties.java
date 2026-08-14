package personal.albiondiscordbot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Albion Online API and battle-poller settings.
 *
 * <p>The default base URL is the EU (Amsterdam) game-info host. The user agent must
 * look like a browser: Cloudflare fronts this API and answers default Java user
 * agents with 403.
 */
@ConfigurationProperties(prefix = "albion")
public record AlbionProperties(Api api, Poller poller, String battleUrlTemplate) {

    /**
     * Link target for a battle in killboard posts, with {@code %d} for the battle id.
     *
     * <p>Configurable because killboard sites are region-scoped — the subdomain has to
     * match the server the guild plays on, or every link 404s.
     */
    public String battleUrl(long albionBattleId) {
        return battleUrlTemplate.formatted(albionBattleId);
    }

    public record Api(
            String baseUrl,
            String userAgent,
            Duration minRequestInterval,
            Duration connectTimeout,
            Duration readTimeout) {
    }

    /**
     * How deep each poll digs is driven by <em>when the last one succeeded</em>, not by a
     * fixed window. Battles are dense — around 560 of them span under two hours on EU —
     * so a fixed multi-hour window would cost dozens of requests every couple of minutes
     * while a fixed page budget would silently stop short of it. Anchoring on the last
     * success makes a routine poll cost two or three pages and a poll after downtime
     * automatically dig as far as it needs to.
     */
    public record Poller(
            boolean enabled,
            Duration interval,
            /* Re-scan this far before the last success, so nothing falls through the gap. */
            Duration overlap,
            /* How far back to reach on the very first run, when there is no last success. */
            Duration coldStartLookback,
            /*
             * A battle keeps accruing participants for ~180s after its last kill
             * (its `timeout` field). Ingesting before then records partial data, so
             * battles younger than this are skipped and picked up on a later run.
             */
            Duration finalizeGrace,
            /* Safety valve only; the time horizon is what normally ends paging. */
            int maxPages) {
    }
}
