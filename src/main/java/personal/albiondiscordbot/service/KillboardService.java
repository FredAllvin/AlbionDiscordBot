package personal.albiondiscordbot.service;

import java.awt.Color;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /** Called once per battle, the first time the poller stores it. */
    @Transactional
    public void onBattleStored(AlbionBattle battle) {
        List<Long> discordGuildIds =
                trackedGuilds.findDiscordGuildIdsTracking(List.copyOf(battle.guilds().keySet()));

        for (Long discordGuildId : discordGuildIds) {
            DiscordGuildConfig config = configs.findById(discordGuildId).orElse(null);
            if (config == null || config.getKillboardChannelId() == null) {
                continue;
            }

            // Both tests must pass. Total size alone would post a handful of us caught
            // in someone else's brawl; our turnout alone would post ten of us ganking
            // three people. A CTA is a big fight that we actually showed up to.
            Set<String> ourGuildIds = ourGuildIds(discordGuildId);
            if (battle.playerCount() < config.getCtaMinTotalPlayers()
                    || ourPlayerCount(battle, ourGuildIds) < config.getCtaMinGuildPlayers()) {
                continue;
            }
            KillboardPost.Key key = new KillboardPost.Key(discordGuildId, battle.id());
            if (posts.existsByKey(key)) {
                continue;
            }

            // Record the post before sending. Discord could succeed while the reply is
            // lost, and a duplicate embed is worse than a missing message id.
            posts.save(new KillboardPost(discordGuildId, battle.id(), null));
            send(config, battle, ourGuildIds);
        }
    }

    /** How many tracked-guild members fought in this battle. */
    static long ourPlayerCount(AlbionBattle battle, Set<String> ourGuildIds) {
        return battle.players().values().stream()
                .filter(p -> p.guildId() != null && ourGuildIds.contains(p.guildId()))
                .count();
    }

    private Set<String> ourGuildIds(long discordGuildId) {
        return trackedGuilds.findByDiscordGuildId(discordGuildId).stream()
                .map(personal.albiondiscordbot.domain.TrackedAlbionGuild::getAlbionGuildId)
                .collect(Collectors.toSet());
    }

    private void send(DiscordGuildConfig config, AlbionBattle battle, Set<String> ourGuildIds) {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(config.getKillboardChannelId());
        if (channel == null) {
            log.warn(
                    "Killboard channel {} not found for guild {}",
                    config.getKillboardChannelId(),
                    config.getDiscordGuildId());
            return;
        }

        try {
            channel.sendMessageEmbeds(buildEmbed(battle, ourGuildIds)).queue();
        } catch (RuntimeException e) {
            log.warn("Failed to post killboard for battle {}", battle.id(), e);
        }
    }

    net.dv8tion.jda.api.entities.MessageEmbed buildEmbed(AlbionBattle battle, Set<String> ourGuildIds) {
        List<AlbionBattle.Side> sides = battle.guilds().values().stream()
                .sorted(Comparator.comparingLong(AlbionBattle.Side::killFame).reversed())
                .toList();

        String opponent = sides.stream()
                .filter(s -> !ourGuildIds.contains(s.id()))
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

        long ourKills = 0;
        long ourDeaths = 0;
        long ourFame = 0;
        long ourPlayers = 0;
        for (AlbionBattle.Participant p : battle.players().values()) {
            if (p.guildId() != null && ourGuildIds.contains(p.guildId())) {
                ourKills += p.kills();
                ourDeaths += p.deaths();
                ourFame += p.killFame();
                ourPlayers++;
            }
        }

        embed.addField("Our side", "%d players\n%d kills / %d deaths\n%s fame"
                .formatted(ourPlayers, ourKills, ourDeaths, Formatting.compact(ourFame)), true);
        embed.addField("Battle", "%d players\n%d kills\n%s fame"
                .formatted(battle.playerCount(), battle.totalKills(), Formatting.compact(battle.totalFame())), true);

        if (battle.startTime() != null && battle.endTime() != null) {
            Duration duration = Duration.between(battle.startTime(), battle.endTime());
            embed.addField("Duration", "%dm %ds".formatted(duration.toMinutes(), duration.toSecondsPart()), true);
        }

        String topGuilds = sides.stream()
                .limit(6)
                .map(s -> "%s **%s** — %d/%d, %s"
                        .formatted(
                                ourGuildIds.contains(s.id()) ? "🟢" : "🔴",
                                Formatting.escapeMarkdown(s.name()),
                                s.kills(),
                                s.deaths(),
                                Formatting.compact(s.killFame())))
                .collect(Collectors.joining("\n"));
        if (!topGuilds.isBlank()) {
            embed.addField("Guilds (kills/deaths, fame)", topGuilds, false);
        }

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

        // The battle id is what /payout-cta takes, so make it copyable rather than
        // making officers dig it out of the link.
        embed.setFooter("Battle " + battle.id() + " · pay everyone here with /payout-cta");

        return embed.build();
    }
}
