package personal.albiondiscordbot.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Objective;
import personal.albiondiscordbot.repository.ObjectiveRepository;
import personal.albiondiscordbot.util.Formatting;
import personal.albiondiscordbot.util.UtcTimeParser;

/**
 * The objective list behind {@code /objective add} and {@code /objective show}.
 *
 * <p>Nothing schedules the expiry — there is no job and no timer. An objective is dropped
 * the next time somebody reads or writes the list, which is the only moment it can matter,
 * and it means a restart cannot leave stale entries behind because there was no process
 * alive to expire them at the time.
 *
 * <p>{@code now} is a parameter rather than an {@code Instant.now()} inside these methods.
 * The whole feature is a clock comparison, so a test that cannot move the clock cannot
 * check any of it without sleeping through the window it is testing.
 */
@Service
public class ObjectiveService {

    /**
     * How long an objective stays on the list after it has popped.
     *
     * <p>Not zero, because the list is read by people on their way to the objective. A
     * chest that popped ten minutes ago is the most useful line on the board — it is
     * where everyone already is. It is only once nobody could still be going that the
     * entry becomes noise.
     */
    public static final Duration GRACE = Duration.ofMinutes(30);

    private final ObjectiveRepository objectives;

    public ObjectiveService(ObjectiveRepository objectives) {
        this.objectives = objectives;
    }

    /**
     * Adds an objective at the next occurrence of {@code popsAtUtc}.
     *
     * @throws CommandException if the same name is already on the list at that time
     */
    @Transactional
    public Objective add(long discordGuildId, long callerId, String name, LocalTime popsAtUtc, Instant now) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new CommandException(
                    "Give the objective a name, for example "
                            + "`/objective add name:Fort Sterling chest time:20:00`.");
        }
        expire(discordGuildId, now);

        Instant popsAt = UtcTimeParser.nextOccurrence(popsAtUtc, now);
        if (objectives.existsByDiscordGuildIdAndNameIgnoreCaseAndPopsAt(discordGuildId, trimmed, popsAt)) {
            throw new CommandException(duplicateMessage(trimmed, popsAtUtc));
        }
        try {
            return objectives.saveAndFlush(new Objective(discordGuildId, trimmed, popsAt, callerId));
        } catch (DataIntegrityViolationException e) {
            // ux_objective_name_time. Two people relaying the same intel at once both pass
            // the check above, and the second one lands here.
            throw new CommandException(duplicateMessage(trimmed, popsAtUtc), e);
        }
    }

    /** The live list, soonest first, with anything past the grace window dropped. */
    @Transactional
    public List<Objective> list(long discordGuildId, Instant now) {
        expire(discordGuildId, now);
        return objectives.findByDiscordGuildIdOrderByPopsAtAsc(discordGuildId);
    }

    /**
     * Deletes objectives the clock has moved past by more than {@link #GRACE}.
     *
     * @return how many were dropped
     */
    @Transactional
    public int expire(long discordGuildId, Instant now) {
        return objectives.deleteExpired(discordGuildId, now.minus(GRACE));
    }

    /** Whether an objective has popped already and is living out its grace window. */
    public static boolean hasPopped(Objective objective, Instant now) {
        return !objective.getPopsAt().isAfter(now);
    }

    private static String duplicateMessage(String name, LocalTime popsAtUtc) {
        return ("**%s** is already on the list for `%s` UTC. Add it at another time, or leave "
                        + "it — somebody has already called it.")
                .formatted(Formatting.escapeMarkdown(name), popsAtUtc);
    }
}
