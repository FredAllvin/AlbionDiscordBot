package personal.albiondiscordbot.service;

import java.awt.Color;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.domain.KillboardPost;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.KillboardPostRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.util.Formatting;

/**
 * Posts large battles our guilds fought in to each server's killboard channel.
 *
 * <p>Driven by the battle poller rather than a second API loop — the poller already
 * holds the battle's complete JSON when it finalizes, including the enemy guild and
 * alliance maps. The embed is built from that in-memory object and discarded, which is
 * why enemy rosters never need storing.
 *
 * <p>Posting is exactly-once in both directions, and the two halves come from different
 * places. <em>At most once</em> is the {@code killboard_post} primary key. <em>At least
 * once</em> is the poller offering the same battle on every run it appears in, with a row
 * written only after Discord has taken the message — so a send that fails is retried
 * rather than mistaken for one that worked.
 */
@Service
public class KillboardService {

    private static final Logger log = LoggerFactory.getLogger(KillboardService.class);

    private final ObjectProvider<JDA> jdaProvider;
    private final DiscordGuildConfigRepository configs;
    private final TrackedAlbionGuildRepository trackedGuilds;
    private final KillboardPostRepository posts;
    private final AlbionProperties albionProperties;

    public KillboardService(
            ObjectProvider<JDA> jdaProvider,
            DiscordGuildConfigRepository configs,
            TrackedAlbionGuildRepository trackedGuilds,
            KillboardPostRepository posts,
            AlbionProperties albionProperties) {
        this.jdaProvider = jdaProvider;
        this.configs = configs;
        this.trackedGuilds = trackedGuilds;
        this.posts = posts;
        this.albionProperties = albionProperties;
    }

    /**
     * Offered every finalized battle a tracked guild fought in, on every poll — not only
     * the first time one is stored.
     *
     * <p>Keeping a battle to one post is {@code killboard_post}'s primary key doing its
     * job, not the caller's. Handing over every sighting is what makes a refused post
     * recoverable at all: the poller re-scans an overlapping window every minute, so a
     * killboard Discord would not take is simply tried again on the next run — the same
     * reason re-reading a battle costs the ingest nothing.
     */
    public void onBattleFinalized(AlbionBattle battle) {
        List<Long> discordGuildIds =
                trackedGuilds.findDiscordGuildIdsTracking(List.copyOf(battle.guilds().keySet()));

        for (Long discordGuildId : discordGuildIds) {
            // Cheapest guard first: now that every sighting arrives here, almost all of
            // them are battles this server has already been shown.
            if (posts.existsByKey(new KillboardPost.Key(discordGuildId, battle.id()))) {
                continue;
            }
            DiscordGuildConfig config = configs.findById(discordGuildId).orElse(null);
            if (config == null || config.getKillboardChannelId() == null) {
                continue;
            }

            // Both tests must pass. Total size alone would post a handful of us caught
            // in someone else's brawl; our turnout alone would post ten of us ganking
            // three people. A CTA is a big fight that we actually showed up to.
            //
            // Our turnout counts tracked guilds only, never allies: the question this
            // threshold answers is "did enough of OUR people show up to be worth a post
            // and a /split-cta", and an alliance-mate bringing thirty does not make our
            // five into a CTA. Allies are folded into the embed's "our side" totals
            // further down, where the question is what the fight looked like instead.
            Set<String> ourGuildIds = ourGuildIds(discordGuildId);
            if (battle.playerCount() < config.getCtaMinTotalPlayers()
                    || ourPlayerCount(battle, ourGuildIds) < config.getCtaMinGuildPlayers()) {
                continue;
            }

            // Recorded only once Discord has actually taken it. The other order — claim
            // the post, then send — loses a killboard permanently on any refusal, because
            // nothing revisits a battle that is already marked posted: a missing Send
            // Messages permission, a rate limit, a deploy landing mid-send, and the fight
            // is simply never posted and never retried. What this order risks instead is
            // one duplicate embed, if the insert fails in the window between the send
            // returning and the commit. That window is milliseconds wide, and a duplicate
            // is a worse-looking channel rather than a CTA nobody gets told about.
            send(config, battle, ourGuildIds)
                    .ifPresent(messageId -> posts.save(new KillboardPost(discordGuildId, battle.id(), messageId)));
        }
    }

    /** How many tracked-guild members fought in this battle. Never counts allies. */
    static long ourPlayerCount(AlbionBattle battle, Set<String> ourGuildIds) {
        return battle.players().values().stream()
                .filter(p -> p.guildId() != null && ourGuildIds.contains(p.guildId()))
                .count();
    }

    /**
     * Guilds that fought this battle under the same alliance banner as one of ours.
     *
     * <p>Read from the battle rather than from {@code tracked_albion_guild.alliance_id},
     * which is a snapshot taken when {@code /guild add} ran and goes stale the moment
     * anyone changes alliance. The battle records who stood with whom that day, which is
     * the thing being drawn.
     *
     * <p>Guilds with no alliance report {@code ""} rather than null — verified against 20
     * live EU battles on 15 August 2026, e.g. {@code "Dark Legion0"} in battle 413360289.
     * Matching on blank would file every unallied guild in the fight as an ally of every
     * other one, so blanks are dropped on both sides.
     */
    static Set<String> allyGuildIds(AlbionBattle battle, Set<String> ourGuildIds) {
        Set<String> ourAlliances = battle.guilds().values().stream()
                .filter(s -> ourGuildIds.contains(s.id()))
                .map(AlbionBattle.Side::allianceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        if (ourAlliances.isEmpty()) {
            return Set.of();
        }
        return battle.guilds().values().stream()
                .filter(s -> !ourGuildIds.contains(s.id()))
                .filter(s -> s.allianceId() != null && ourAlliances.contains(s.allianceId()))
                .map(AlbionBattle.Side::id)
                .collect(Collectors.toSet());
    }

    private Set<String> ourGuildIds(long discordGuildId) {
        return trackedGuilds.findByDiscordGuildId(discordGuildId).stream()
                .map(personal.albiondiscordbot.domain.TrackedAlbionGuild::getAlbionGuildId)
                .collect(Collectors.toSet());
    }

    /**
     * @return the id of the message Discord accepted, or empty if it did not take it —
     *     in which case nothing is recorded and the next poll offers the battle again
     */
    private Optional<Long> send(DiscordGuildConfig config, AlbionBattle battle, Set<String> ourGuildIds) {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            // Still connecting. Nothing is lost by saying so and waiting a minute.
            return Optional.empty();
        }
        TextChannel channel = jda.getTextChannelById(config.getKillboardChannelId());
        if (channel == null) {
            log.warn(
                    "Killboard channel {} not found for guild {}",
                    config.getKillboardChannelId(),
                    config.getDiscordGuildId());
            return Optional.empty();
        }

        try {
            // complete() rather than queue(): queue() hands the request off and returns
            // immediately, so a rejection surfaces on a JDA thread long after this method
            // and its catch block are gone — which is how a failed send used to look
            // exactly like a successful one. Blocking the poller thread for one message is
            // the price of knowing. It cannot stall the poller either: runs are
            // single-flight and anchored on the last success, so a slow send delays the
            // next poll rather than skipping anything.
            Message message = channel.sendMessageEmbeds(buildEmbed(battle, ourGuildIds)).complete();
            return Optional.of(message.getIdLong());
        } catch (RuntimeException e) {
            log.warn("Failed to post killboard for battle {}; retrying on the next poll", battle.id(), e);
            return Optional.empty();
        }
    }

    net.dv8tion.jda.api.entities.MessageEmbed buildEmbed(AlbionBattle battle, Set<String> ourGuildIds) {
        List<AlbionBattle.Side> sides = battle.guilds().values().stream()
                .sorted(Comparator.comparingLong(AlbionBattle.Side::killFame).reversed())
                .toList();

        Set<String> allyGuildIds = allyGuildIds(battle, ourGuildIds);

        // Allies are excluded here too, not just coloured differently. The title names the
        // biggest side that is not us, and without this an alliance-mate who out-famed the
        // enemy would headline the post as the guild we fought.
        String opponent = sides.stream()
                .filter(s -> !ourGuildIds.contains(s.id()) && !allyGuildIds.contains(s.id()))
                .map(AlbionBattle.Side::name)
                .findFirst()
                .orElse("unknown");

        EmbedBuilder embed = new EmbedBuilder()
                // No zone name is available: the API's clusterName is always null,
                // so the opponent is the most useful thing to title this with.
                .setTitle(
                        "%d-player battle vs %s".formatted(battle.playerCount(), opponent),
                        albionProperties.battleUrl(battle.id()))
                .setColor(new Color(0xC0392B))
                .setTimestamp(battle.startTime());

        // "Our side" is the whole friendly force, allies included — that is what the fight
        // actually looked like from our position. The split is still spelled out below,
        // because the guild-only figure is the one the CTA threshold and /split-cta work
        // from, and a reader comparing the post against those needs to see both numbers.
        long ourKills = 0;
        long ourDeaths = 0;
        long ourFame = 0;
        long ourPlayers = 0;
        long allyPlayers = 0;
        for (AlbionBattle.Participant p : battle.players().values()) {
            boolean ours = p.guildId() != null && ourGuildIds.contains(p.guildId());
            boolean allied = p.guildId() != null && allyGuildIds.contains(p.guildId());
            if (!ours && !allied) {
                continue;
            }
            ourKills += p.kills();
            ourDeaths += p.deaths();
            ourFame += p.killFame();
            if (ours) {
                ourPlayers++;
            } else {
                allyPlayers++;
            }
        }

        String roster = allyPlayers == 0
                ? "%d players".formatted(ourPlayers)
                : "%d players (%d us, %d allied)".formatted(ourPlayers + allyPlayers, ourPlayers, allyPlayers);
        embed.addField("Our side", "%s\n%d kills / %d deaths\n%s fame"
                .formatted(roster, ourKills, ourDeaths, Formatting.compact(ourFame)), true);
        embed.addField("Battle", "%d players\n%d kills\n%s fame"
                .formatted(battle.playerCount(), battle.totalKills(), Formatting.compact(battle.totalFame())), true);

        if (battle.startTime() != null && battle.endTime() != null) {
            Duration duration = Duration.between(battle.startTime(), battle.endTime());
            embed.addField("Duration", "%dm %ds".formatted(duration.toMinutes(), duration.toSecondsPart()), true);
        }

        // 🟢 us, 🟣 alliance-mates, 🔴 the people we were shooting at.
        String topGuilds = sides.stream()
                .limit(6)
                .map(s -> "%s **%s** — %d/%d, %s"
                        .formatted(
                                ourGuildIds.contains(s.id())
                                        ? "🟢"
                                        : allyGuildIds.contains(s.id()) ? "🟣" : "🔴",
                                Formatting.escapeMarkdown(s.name()),
                                s.kills(),
                                s.deaths(),
                                Formatting.compact(s.killFame())))
                .collect(Collectors.joining("\n"));
        if (!topGuilds.isBlank()) {
            embed.addField("Guilds (kills/deaths, fame)", topGuilds, false);
        }

        // Our guilds only, allies excluded on purpose: this field is recognition for our
        // own members, and it sits next to a footer pointing at /split-cta, which can only
        // ever pay people registered with this server.
        String topPlayers = battle.players().values().stream()
                .filter(p -> p.guildId() != null && ourGuildIds.contains(p.guildId()))
                .sorted(Comparator.comparingLong(AlbionBattle.Participant::killFame).reversed())
                .limit(3)
                .map(p -> "**%s** — %d kills, %s fame"
                        .formatted(Formatting.escapeMarkdown(p.name()), p.kills(), Formatting.compact(p.killFame())))
                .collect(Collectors.joining("\n"));
        if (!topPlayers.isBlank()) {
            embed.addField("Our top performers", topPlayers, false);
        }

        // The battle id is what /split-cta takes, so make it copyable rather than making
        // officers dig it out of the link. Naming the command correctly matters more than
        // usual here: /payout also exists and moves silver the other way, so an officer
        // following a wrong hint would clear balances instead of crediting them.
        //
        // The comma hint earns its space: a CTA that broke into several killboards gets
        // several of these posts, and splitting each one separately pays whoever stayed
        // for all of them once per post.
        embed.setFooter("Battle " + battle.id()
                + " · credit everyone here with /split-cta — comma-separate ids to merge fights");

        return embed.build();
    }
}
