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

    public int ctaMinPlayers() {
        return config != null ? config.getCtaMinPlayers() : 10;
    }
}
