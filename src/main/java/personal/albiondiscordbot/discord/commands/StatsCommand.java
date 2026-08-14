package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.service.RegistrationService;
import personal.albiondiscordbot.service.StatsService;
import personal.albiondiscordbot.util.Formatting;

/** {@code /stats [@user]} — kills, deaths, fame and CTA attendance since joining. */
@Component
public class StatsCommand implements SlashCommand {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final RegistrationService registrationService;
    private final StatsService statsService;

    public StatsCommand(RegistrationService registrationService, StatsService statsService) {
        this.registrationService = registrationService;
        this.statsService = statsService;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public boolean ephemeral() {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("stats", "Show a member's kills, deaths, fame and CTA attendance")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(new OptionData(
                        OptionType.USER, "user", "Whose stats to show (default yourself)", false));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        Member target = event.getOption("user", OptionMapping::getAsMember);
        Member subject = target != null ? target : context.member();

        Registration registration = registrationService
                .find(context.guildId(), subject.getIdLong())
                .orElseThrow(() -> new CommandException(
                        target == null
                                ? "You are not registered. Use `/register <your character name>` first."
                                : "%s is not registered.".formatted(subject.getAsMention())));

        StatsService.PlayerStats stats = statsService.compute(registration, context.ctaMinPlayers());

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(0x3498DB))
                .setAuthor(subject.getEffectiveName(), null, subject.getEffectiveAvatarUrl())
                .setTitle(Formatting.escapeMarkdown(stats.characterName()))
                .setDescription("Tracked since **%s**".formatted(DATE.format(stats.trackedSince())));

        StatsService.BattleTotals battles = stats.battles();
        embed.addField(
                "CTAs attended",
                "**%d**\n_battles over %d players_".formatted(battles.ctas(), context.ctaMinPlayers()),
                true);
        embed.addField("Battles tracked", Long.toString(battles.battles()), true);
        embed.addField("K / D", "%d / %d  (%s)".formatted(battles.kills(), battles.deaths(), battles.killDeathRatio()), true);

        embed.addField(
                "In tracked battles",
                "Kills **%d**\nDeaths **%d**\nKill fame **%s**"
                        .formatted(battles.kills(), battles.deaths(), Formatting.compact(battles.killFame())),
                true);

        StatsService.FameDelta fame = stats.fame();
        if (fame.available()) {
            embed.addField(
                    "All activity (from profile)",
                    "Kill fame **%s**\nDeath fame **%s**\nPvE fame **%s**"
                            .formatted(
                                    Formatting.compact(fame.killFame()),
                                    Formatting.compact(fame.deathFame()),
                                    Formatting.compact(fame.pveFame())),
                    true);
        } else {
            embed.addField(
                    "All activity (from profile)",
                    "_No baseline was captured at registration, so totals since joining cannot be calculated._",
                    true);
        }

        if (!stats.verified()) {
            embed.addField(
                    "Force-registered",
                    "This character's guild membership was never verified.",
                    false);
        }

        embed.setFooter(
                "Battle stats only count fights the bot has seen. Attendance counts players who scored a kill, "
                        + "died, or earned assist fame, so it is a lower bound.");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }
}
