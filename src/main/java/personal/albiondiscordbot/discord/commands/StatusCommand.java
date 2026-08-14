package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.PollerState;
import personal.albiondiscordbot.repository.BattleParticipationRepository;
import personal.albiondiscordbot.repository.BattleRepository;
import personal.albiondiscordbot.repository.PollerStateRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.service.BalanceService;
import personal.albiondiscordbot.util.Formatting;

/**
 * {@code /status} — is the bot actually working?
 *
 * <p>The failure mode this exists for is silent: if Cloudflare starts rejecting the
 * Albion API, the poller keeps running, nothing errors visibly in Discord, and stats
 * simply stop growing. Nobody notices for weeks. This surfaces the poller's own record
 * of its last success and failure streak so that goes from invisible to obvious.
 */
@Component
public class StatusCommand implements SlashCommand {

    /** Beyond this without a successful poll, something is wrong. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(15);

    private final PollerStateRepository pollerStates;
    private final RegistrationRepository registrations;
    private final TrackedAlbionGuildRepository trackedGuilds;
    private final BattleRepository battles;
    private final BattleParticipationRepository participations;
    private final BalanceService balances;
    private final ObjectProvider<JDA> jdaProvider;

    public StatusCommand(
            PollerStateRepository pollerStates,
            RegistrationRepository registrations,
            TrackedAlbionGuildRepository trackedGuilds,
            BattleRepository battles,
            BattleParticipationRepository participations,
            BalanceService balances,
            ObjectProvider<JDA> jdaProvider) {
        this.pollerStates = pollerStates;
        this.registrations = registrations;
        this.trackedGuilds = trackedGuilds;
        this.battles = battles;
        this.participations = participations;
        this.balances = balances;
        this.jdaProvider = jdaProvider;
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public boolean staffOnly() {
        return true;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("status", "Check that the bot and its battle tracking are healthy")
                .setContexts(InteractionContextType.GUILD);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        PollerState poller = pollerStates.findById(PollerState.BATTLES).orElse(null);

        boolean healthy = poller != null
                && poller.getConsecutiveFailures() == 0
                && poller.getLastSuccessAt() != null
                && Duration.between(poller.getLastSuccessAt(), Instant.now()).compareTo(STALE_AFTER) < 0;

        int trackedGuildCount = trackedGuilds.findByDiscordGuildId(context.guildId()).size();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(healthy ? "All good" : "Needs attention")
                .setColor(healthy ? new Color(0x2ECC71) : new Color(0xE67E22));

        embed.addField("Battle poller", pollerSummary(poller, trackedGuildCount), false);

        embed.addField("Registered members",
                Long.toString(registrations.countByDiscordGuildIdAndActiveTrue(context.guildId())), true);
        embed.addField("Tracked guilds", Integer.toString(trackedGuildCount), true);
        embed.addField("Silver on the books",
                Formatting.silver(balances.totalSilver(context.guildId())), true);

        embed.addField("Battles stored", Formatting.silver(battles.count()), true);
        embed.addField("Attendance records", Formatting.silver(participations.count()), true);

        JDA jda = jdaProvider.getIfAvailable();
        embed.addField("Discord gateway",
                jda == null ? "unavailable" : jda.getGatewayPing() + " ms", true);

        StringBuilder config = new StringBuilder();
        config.append(context.config().getStaffRoleId() == null ? "❌" : "✅").append(" staff role\n");
        config.append(context.config().getVerifiedRoleId() == null ? "❌" : "✅").append(" verified role\n");
        config.append(context.config().getKillboardChannelId() == null ? "❌" : "✅").append(" killboard channel\n");
        config.append(context.config().getLogChannelId() == null ? "❌" : "✅").append(" audit log channel\n");
        config.append(trackedGuildCount == 0 ? "❌" : "✅").append(" in-game guild");
        embed.addField("Configuration", config.toString(), false);

        embed.addField("CTA threshold", "more than %d players".formatted(context.ctaMinPlayers()), true);

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private String pollerSummary(PollerState poller, int trackedGuildCount) {
        if (poller == null) {
            return "❌ No poller state recorded. The bot may not have finished starting.";
        }
        if (trackedGuildCount == 0) {
            return "⏸ Idle — no in-game guild is tracked yet, so there is nothing to look for. "
                    + "Add one with `/guild add <name>`.";
        }
        StringBuilder summary = new StringBuilder();

        if (poller.getLastSuccessAt() == null) {
            summary.append("❌ Has never completed a poll successfully.\n");
        } else {
            Duration since = Duration.between(poller.getLastSuccessAt(), Instant.now());
            boolean stale = since.compareTo(STALE_AFTER) >= 0;
            summary.append(stale ? "⚠️ " : "✅ ")
                    .append("Last successful poll <t:")
                    .append(poller.getLastSuccessAt().getEpochSecond())
                    .append(":R>\n");
        }

        if (poller.getConsecutiveFailures() > 0) {
            summary.append("❌ ")
                    .append(poller.getConsecutiveFailures())
                    .append(" consecutive failure(s). If this keeps climbing, check the logs — a 403 "
                            + "usually means Cloudflare rejected the configured user agent.\n");
        }
        if (poller.getFirstIngestAt() != null) {
            summary.append("📊 Tracking battles since <t:")
                    .append(poller.getFirstIngestAt().getEpochSecond())
                    .append(":D>");
        } else {
            summary.append("📊 No battles stored yet — this is normal until your guild fights one.");
        }
        return summary.toString();
    }
}
