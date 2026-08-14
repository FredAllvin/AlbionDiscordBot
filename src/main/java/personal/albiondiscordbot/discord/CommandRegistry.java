package personal.albiondiscordbot.discord;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Indexes every {@link SlashCommand} bean by name. Duplicate names fail at startup
 * rather than silently shadowing each other.
 */
@Component
public class CommandRegistry {

    private final Map<String, SlashCommand> byName;

    public CommandRegistry(List<SlashCommand> commands) {
        Map<String, SlashCommand> map = new LinkedHashMap<>();
        for (SlashCommand command : commands) {
            SlashCommand existing = map.put(command.name(), command);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate slash command name '%s': %s and %s"
                                .formatted(
                                        command.name(),
                                        existing.getClass().getName(),
                                        command.getClass().getName()));
            }
        }
        this.byName = Map.copyOf(map);
    }

    public Optional<SlashCommand> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<SlashCommand> all() {
        return byName.values();
    }
}
