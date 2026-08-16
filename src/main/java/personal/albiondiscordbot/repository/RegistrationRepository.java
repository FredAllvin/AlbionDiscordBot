package personal.albiondiscordbot.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import personal.albiondiscordbot.domain.Registration;

public interface RegistrationRepository extends JpaRepository<Registration, Long> {

    Optional<Registration> findByDiscordGuildIdAndDiscordUserIdAndActiveTrue(
            Long discordGuildId, Long discordUserId);

    Optional<Registration> findByDiscordGuildIdAndAlbionPlayerIdAndActiveTrue(
            Long discordGuildId, String albionPlayerId);

    Optional<Registration> findByDiscordGuildIdAndAlbionPlayerNameLowerAndActiveTrue(
            Long discordGuildId, String albionPlayerNameLower);

    List<Registration> findByDiscordGuildIdAndActiveTrue(Long discordGuildId);

    long countByDiscordGuildIdAndActiveTrue(Long discordGuildId);

    /**
     * Registered members of this Discord server who fought in <em>any</em> of the given
     * battles, each returned once.
     *
     * <p>This is what lets {@code /split-cta} credit the people who actually showed up,
     * without anyone hand-tagging them first.
     *
     * <p>Takes a list because one CTA is often several battles on the killboard. Selecting
     * registrations rather than participation rows is what makes the union free: someone
     * who fought in all three fights still matches once, so merging fights cannot pay
     * anyone twice.
     */
    @Query("""
            SELECT r FROM Registration r
            WHERE r.discordGuildId = :discordGuildId
              AND r.active = true
              AND r.albionPlayerId IN (
                  SELECT p.albionPlayerId FROM BattleParticipation p
                  WHERE p.albionBattleId IN :albionBattleIds)
            """)
    List<Registration> findParticipantsOfBattles(
            Long discordGuildId, Collection<Long> albionBattleIds);

    /**
     * Active registrations whose last API check is oldest, so a background sweep can
     * freshen a few at a time rather than hammering the API with the whole roster.
     * Never-checked ones come first.
     */
    @Query("""
            SELECT r FROM Registration r
            WHERE r.active = true
              AND (r.lastValidatedAt IS NULL OR r.lastValidatedAt < :staleBefore)
            ORDER BY r.lastValidatedAt ASC NULLS FIRST
            """)
    List<Registration> findStalest(Instant staleBefore, Pageable pageable);

    /** Registrations the last sweep found are no longer in a tracked guild. */
    List<Registration> findByDiscordGuildIdAndActiveTrueAndLastValidationOkFalse(Long discordGuildId);
}
