package personal.albiondiscordbot.repository;

import java.util.List;
import java.util.Optional;
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
     * Registered members of this Discord server who fought in the given battle.
     *
     * <p>This is what lets {@code /payout-cta} pay the people who actually showed up,
     * without anyone hand-tagging them first.
     */
    @Query("""
            SELECT r FROM Registration r
            WHERE r.discordGuildId = :discordGuildId
              AND r.active = true
              AND r.albionPlayerId IN (
                  SELECT p.albionPlayerId FROM BattleParticipation p
                  WHERE p.albionBattleId = :albionBattleId)
            """)
    List<Registration> findParticipantsOfBattle(Long discordGuildId, Long albionBattleId);
}
