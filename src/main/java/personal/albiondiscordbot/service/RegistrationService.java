package personal.albiondiscordbot.service;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionPlayerDetail;
import personal.albiondiscordbot.albion.dto.AlbionSearchResponse;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.FameBaseline;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final AlbionApiClient albion;
    private final RegistrationRepository registrations;
    private final TrackedAlbionGuildRepository trackedGuilds;

    public RegistrationService(
            AlbionApiClient albion,
            RegistrationRepository registrations,
            TrackedAlbionGuildRepository trackedGuilds) {
        this.albion = albion;
        this.registrations = registrations;
        this.trackedGuilds = trackedGuilds;
    }

    /**
     * Registers a character to a Discord user after confirming it exists and belongs to
     * one of this server's tracked guilds.
     *
     * <p>This proves the <em>character</em> is in the guild. It cannot prove the Discord
     * user owns that character — impersonation is handled after the fact with
     * {@code /unregister}, which is why the audit fields exist.
     */
    @Transactional
    public Registration register(long discordGuildId, long discordUserId, String characterName) {
        List<TrackedAlbionGuild> tracked = trackedGuilds.findByDiscordGuildId(discordGuildId);
        if (tracked.isEmpty()) {
            throw new CommandException(
                    "No in-game guild is configured yet. Staff need to run `/guild add <guild name>` first.");
        }

        AlbionSearchResponse.PlayerHit hit = albion.findPlayerByExactName(characterName)
                .orElseThrow(() -> new CommandException(
                        "No Albion character called **%s** exists on the EU server. Check the spelling."
                                .formatted(characterName)));

        // /players/{id} is authoritative for current guild; search results can lag.
        AlbionPlayerDetail detail = albion.getPlayer(hit.id())
                .orElseThrow(() -> new CommandException(
                        "Found **%s** but could not read their profile. Try again in a moment."
                                .formatted(hit.name())));

        boolean inTrackedGuild = detail.guildId() != null
                && tracked.stream().anyMatch(t -> t.getAlbionGuildId().equals(detail.guildId()));

        if (!inTrackedGuild) {
            throw new CommandException(
                    "**%s** is not in %s. They are currently in **%s**."
                            .formatted(
                                    detail.name(),
                                    tracked.size() == 1
                                            ? "**" + tracked.get(0).getAlbionGuildName() + "**"
                                            : "any guild this server tracks",
                                    detail.guildName() == null || detail.guildName().isBlank()
                                            ? "no guild"
                                            : detail.guildName()));
        }

        return store(discordGuildId, discordUserId, detail.id(), detail.name(), baselineFrom(detail), null);
    }

    /** Registers without verifying guild membership. */
    @Transactional
    public Registration forceRegister(
            long discordGuildId, long discordUserId, String characterName, long actorId) {

        // Still try to resolve the real character so ids and stats work where possible.
        Optional<AlbionSearchResponse.PlayerHit> hit;
        try {
            hit = albion.findPlayerByExactName(characterName);
        } catch (RuntimeException e) {
            log.warn("Albion lookup failed during force-register of '{}'", characterName, e);
            hit = Optional.empty();
        }

        String playerId = hit.map(AlbionSearchResponse.PlayerHit::id).orElse("UNRESOLVED:" + characterName);
        String playerName = hit.map(AlbionSearchResponse.PlayerHit::name).orElse(characterName);

        FameBaseline baseline = hit.flatMap(h -> {
                    try {
                        return albion.getPlayer(h.id());
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                })
                .map(this::baselineFrom)
                .orElseGet(FameBaseline::unavailable);

        return store(discordGuildId, discordUserId, playerId, playerName, baseline, actorId);
    }

    private Registration store(
            long discordGuildId,
            long discordUserId,
            String albionPlayerId,
            String albionPlayerName,
            FameBaseline baseline,
            Long forcedByActorId) {

        registrations
                .findByDiscordGuildIdAndAlbionPlayerIdAndActiveTrue(discordGuildId, albionPlayerId)
                .ifPresent(existing -> {
                    if (existing.getDiscordUserId() != discordUserId) {
                        throw new CommandException(
                                "**%s** is already registered to <@%d>. If that is wrong, staff can run `/unregister`."
                                        .formatted(albionPlayerName, existing.getDiscordUserId()));
                    }
                });

        // Replace any previous claim by this user rather than stacking rows; the
        // partial unique index would reject a second active registration anyway.
        registrations
                .findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(discordGuildId, discordUserId)
                .ifPresent(previous -> {
                    previous.deactivate(discordUserId);
                    registrations.save(previous);
                });

        Registration registration =
                new Registration(discordGuildId, discordUserId, albionPlayerId, albionPlayerName);
        registration.setFameBaseline(baseline);
        registration.recordValidation(true);
        if (forcedByActorId != null) {
            registration.markForceRegistered(forcedByActorId);
        }
        return registrations.save(registration);
    }

    @Transactional
    public Optional<Registration> unregister(long discordGuildId, long discordUserId, long actorId) {
        Optional<Registration> found =
                registrations.findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(discordGuildId, discordUserId);
        found.ifPresent(registration -> {
            registration.deactivate(actorId);
            registrations.save(registration);
        });
        return found;
    }

    @Transactional(readOnly = true)
    public Optional<Registration> find(long discordGuildId, long discordUserId) {
        return registrations.findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(discordGuildId, discordUserId);
    }

    @Transactional(readOnly = true)
    public List<Registration> allActive(long discordGuildId) {
        return registrations.findByDiscordGuildIdAndActiveTrue(discordGuildId);
    }

    /**
     * Re-checks a registration against the live API.
     *
     * <p>Deliberately uses {@code /players/{id}} rather than {@code /guilds/{id}/members}:
     * the members endpoint is known to be stale and lists characters that left the guild
     * long ago, which would make an audit pass people it should catch.
     */
    public boolean stillInTrackedGuild(Registration registration, List<TrackedAlbionGuild> tracked) {
        if (registration.getAlbionPlayerId().startsWith("UNRESOLVED:")) {
            return false;
        }
        Optional<AlbionPlayerDetail> detail = albion.getPlayer(registration.getAlbionPlayerId());
        if (detail.isEmpty()) {
            return false;
        }
        String guildId = detail.get().guildId();
        return guildId != null && tracked.stream().anyMatch(t -> t.getAlbionGuildId().equals(guildId));
    }

    @Transactional
    public void recordValidation(Registration registration, boolean ok) {
        registration.recordValidation(ok);
        registrations.save(registration);
    }

    private FameBaseline baselineFrom(AlbionPlayerDetail detail) {
        return FameBaseline.of(
                detail.killFameOrZero(),
                detail.deathFameOrZero(),
                detail.pveFame(),
                detail.gatheringFame(),
                detail.craftingFame(),
                detail.fishingFame(),
                detail.farmingFame());
    }
}
