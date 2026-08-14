package personal.albiondiscordbot.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import personal.albiondiscordbot.domain.DiscordGuildConfig;

/**
 * Everything a command needs about where it was invoked: the Discord server, the
 * caller, and the stored configuration for that server.
 *
 * @param guild the Discord server the command ran in
 * @param member the caller
 * @param config stored configuration; absent only for commands that do not require setup
 */
public record CommandContext(Guild guild, Member member, DiscordGuildConfig config) {

    public long guildId() {
        return guild.getIdLong();
    }

    public long callerId() {
        return member.getIdLong();
    }

    /** Minimum size of the whole fight for it to count as a CTA. */
    public int ctaMinTotalPlayers() {
        return config != null ? config.getCtaMinTotalPlayers() : 30;
    }

    /** Minimum number of our own in that fight. Both thresholds must be met. */
    public int ctaMinGuildPlayers() {
        return config != null ? config.getCtaMinGuildPlayers() : 10;
    }

    /** Human-readable form of the pair, e.g. {@code "30+ players, 10+ of ours"}. */
    public String ctaRule() {
        return "%d+ players, %d+ of ours".formatted(ctaMinTotalPlayers(), ctaMinGuildPlayers());
    }
}
