package personal.albiondiscordbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import personal.albiondiscordbot.domain.DiscordGuildConfig;

public interface DiscordGuildConfigRepository extends JpaRepository<DiscordGuildConfig, Long> {
}
