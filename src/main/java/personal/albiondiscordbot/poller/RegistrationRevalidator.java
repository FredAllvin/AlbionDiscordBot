package personal.albiondiscordbot.poller;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.service.RegistrationService;
import personal.albiondiscordbot.service.RegistrationService.MembershipCheck;
import personal.albiondiscordbot.util.Formatting;

/**
 * Keeps registrations honest by re-checking them against the live Albion API.
 *
 * <p>Without this, guild membership is only ever verified at {@code /register} time, so
 * someone who leaves the guild months later stays "verified" forever and keeps the role.
 *
 * <p>Works through the roster a few at a time, oldest check first, rather than sweeping
 * everyone at once. A 300-member guild is refreshed roughly every few hours while each
 * run stays short enough not to make an interactive {@code /register} queue behind it.
 *
 * <p>Departures are <strong>reported, not acted on</strong>. Roles are only removed by
 * {@code /flush-unregistered}, where a human sees the list first — an automated sweep
 * that strips roles on its own is one bad API day away from clearing the whole server.
 */
@Component
@ConditionalOnProperty(
        value = "albion.revalidation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RegistrationRevalidator {

    private static final Logger log = LoggerFactory.getLogger(RegistrationRevalidator.class);

    private final RegistrationRepository registrations;
    private final TrackedAlbionGuildRepository trackedGuilds;
    private final DiscordGuildConfigRepository configs;
    private final RegistrationService registrationService;
    private final ObjectProvider<JDA> jdaProvider;

    private final Duration staleAfter;
    private final int batchSize;

    private final AtomicBoolean running = new AtomicBoolean();

    public RegistrationRevalidator(
            RegistrationRepository registrations,
            TrackedAlbionGuildRepository trackedGuilds,
            DiscordGuildConfigRepository configs,
            RegistrationService registrationService,
            ObjectProvider<JDA> jdaProvider,
            @org.springframework.beans.factory.annotation.Value("${albion.revalidation.stale-after:6h}")
                    Duration staleAfter,
            @org.springframework.beans.factory.annotation.Value("${albion.revalidation.batch-size:40}")
                    int batchSize) {
        this.registrations = registrations;
        this.trackedGuilds = trackedGuilds;
        this.configs = configs;
        this.registrationService = registrationService;
        this.jdaProvider = jdaProvider;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${albion.revalidation.interval:PT30M}",
            initialDelayString = "${albion.revalidation.initial-delay:PT2M}")
    public void revalidate() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Registration revalidation failed", e);
        } finally {
            running.set(false);
        }
    }

    void runOnce() {
        List<Registration> batch = registrations.findStalest(
                Instant.now().minus(staleAfter), PageRequest.of(0, batchSize));

        if (batch.isEmpty()) {
            return;
        }

        int departed = 0;
        for (Registration registration : batch) {
            List<TrackedAlbionGuild> tracked =
                    trackedGuilds.findByDiscordGuildId(registration.getDiscordGuildId());
            if (tracked.isEmpty()) {
                continue;
            }

            MembershipCheck result = registrationService.checkMembership(registration, tracked);
            if (result == MembershipCheck.UNKNOWN) {
                // Leave the previous verdict alone; an API blip is not evidence of anything.
                continue;
            }

            boolean stillIn = result == MembershipCheck.IN_GUILD;
            // Announce the transition only. Without this, every sweep would re-report
            // the same departures until someone got round to flushing them.
            boolean alreadyFlagged = Boolean.FALSE.equals(registration.getLastValidationOk());
            boolean newlyDeparted = !stillIn && !alreadyFlagged;

            registrationService.recordValidation(registration, stillIn);

            if (newlyDeparted) {
                departed++;
                announceDeparture(registration);
            }
        }

        if (departed > 0) {
            log.info("Revalidation found {} member(s) no longer in a tracked guild", departed);
        }
    }

    /** Tells staff, so someone can decide whether to run {@code /flush-unregistered}. */
    private void announceDeparture(Registration registration) {
        DiscordGuildConfig config = configs.findById(registration.getDiscordGuildId()).orElse(null);
        if (config == null || config.getLogChannelId() == null) {
            return;
        }
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(config.getLogChannelId());
        if (channel == null) {
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Left the guild")
                .setColor(new Color(0xE67E22))
                .setDescription("<@%d> (**%s**) is no longer in a tracked in-game guild."
                        .formatted(
                                registration.getDiscordUserId(),
                                Formatting.escapeMarkdown(registration.getAlbionPlayerName())))
                .addField(
                        "What now",
                        "Their registration and balance are untouched. Run `/flush-unregistered confirm:true` "
                                + "to take the verified role off everyone in this state.",
                        false);

        Guild guild = jda.getGuildById(registration.getDiscordGuildId());
        Role verified = guild == null || config.getVerifiedRoleId() == null
                ? null
                : guild.getRoleById(config.getVerifiedRoleId());
        if (verified != null) {
            embed.addField("Still holding", verified.getAsMention(), true);
        }

        try {
            channel.sendMessageEmbeds(embed.build()).queue();
        } catch (RuntimeException e) {
            log.warn("Could not announce departure for {}", registration.getAlbionPlayerName(), e);
        }
    }
}
