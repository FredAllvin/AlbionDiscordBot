package personal.albiondiscordbot.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import personal.albiondiscordbot.domain.SplitBattleGroup;

public interface SplitBattleGroupRepository extends JpaRepository<SplitBattleGroup, String> {

    /**
     * Looked up with the server id as well as the key, so a custom id copied out of one
     * Discord server cannot resolve battles remembered for another.
     */
    Optional<SplitBattleGroup> findByGroupKeyAndDiscordGuildId(String groupKey, Long discordGuildId);
}
