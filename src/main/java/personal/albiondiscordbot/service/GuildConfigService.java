package personal.albiondiscordbot.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;

@Service
public class GuildConfigService {

    private final DiscordGuildConfigRepository repository;

    public GuildConfigService(DiscordGuildConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<DiscordGuildConfig> find(long discordGuildId) {
        return repository.findById(discordGuildId);
    }

    @Transactional(readOnly = true)
    public boolean isSetupComplete(long discordGuildId) {
        return repository.findById(discordGuildId)
                .map(DiscordGuildConfig::isSetupCompleted)
                .orElse(false);
    }

    @Transactional
    public DiscordGuildConfig getOrCreate(long discordGuildId) {
        return repository
                .findById(discordGuildId)
                .orElseGet(() -> repository.save(new DiscordGuildConfig(discordGuildId)));
    }

    @Transactional
    public DiscordGuildConfig save(DiscordGuildConfig config) {
        return repository.save(config);
    }
}
