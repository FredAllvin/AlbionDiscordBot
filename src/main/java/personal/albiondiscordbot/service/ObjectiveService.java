package personal.albiondiscordbot.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Objective;
import personal.albiondiscordbot.repository.ObjectiveRepository;
import personal.albiondiscordbot.util.Formatting;
import personal.albiondiscordbot.util.UtcTimeParser;

/**
 * The objective list behind {@code /objective add|show|edit|remove}.
 *
 * <p>Nothing schedules the expiry — there is no job and no timer. An objective is dropped
 * the next time somebody reads or writes the list, which is the only moment it can matter,
 * and it means a restart cannot leave stale entries behind because there was no process
 * alive to expire them at the time.
 *
 * <p>{@code now} is a parameter rather than an {@code Instant.now()} inside these methods.
 * The whole feature is a clock comparison, so a test that cannot move the clock cannot
 * check any of it without sleeping through the window it is testing.
 *
 * <p>Editing and removing are open to everyone, exactly as adding is. A line that is wrong
 * is worse than no line at all — it sends the group to an empty map at the wrong hour —
 * and whoever spots that is hardly ever whoever typed it, so a correction that has to wait
 * for its author, or for an officer, gives up the thing the board is for. What that costs
 * is accountability, which the caller's own command carries in the channel and the audit
 * log keeps afterwards.
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

    /** Excludes no row from a duplicate check: generated ids start at 1. */
    private static final long NO_ROW = 0L;

    /** How many candidates an "I cannot tell which one you mean" message spells out. */
    private static final int SLOTS_LISTED = 8;

    /**
     * Which line {@code /objective edit} and {@code /objective remove} mean.
     *
     * <p>The name is what the guild calls the thing out loud, and on its own it is
     * normally enough. {@code popsAtUtc} and {@code zone} are tie-breakers, needed only
     * when that name is up more than once; when they are absent and the name is still
     * ambiguous nothing is picked on the caller's behalf — the command refuses and says
     * what the candidates are. Guessing here edits the wrong objective and tells nobody,
     * which is the one failure a shared board cannot survive.
     */
    public record Selector(String name, LocalTime popsAtUtc, String zone) {
    }

    /**
     * What {@code /objective edit} changes. A {@code null} field is left as it is.
     *
     * <p>A blank {@code zone} clears it. Discord sends nothing at all for an option that
     * was left out and offers no way to send an empty one, so without a spelled-out way
     * to say "none" a zone could be corrected but never taken back off.
     */
    public record Change(String name, LocalTime popsAtUtc, String zone) {

        boolean isEmpty() {
            return name == null && popsAtUtc == null && zone == null;
        }
    }

    /**
     * An objective as {@code /objective edit} left it, beside the values it replaced.
     *
     * <p>The old values rather than a rendered summary: an edit is announced in the same
     * channel the original went up in, and people already reading that board need the
     * difference more than the result. Which of the three moved is worked out by the
     * caller, since it is the caller that formats it.
     */
    public record Edited(Objective objective, String previousName, String previousZone, Instant previousPopsAt) {
    }

    private final ObjectiveRepository objectives;

    public ObjectiveService(ObjectiveRepository objectives) {
        this.objectives = objectives;
    }

    /**
     * Adds an objective at the next occurrence of {@code popsAtUtc}.
     *
     * @param zone where it is, or {@code null}/blank for an objective whose name says it
     * @throws CommandException if the same name is already on the list there at that time
     */
    @Transactional
    public Objective add(
            long discordGuildId, long callerId, String name, String zone, LocalTime popsAtUtc, Instant now) {
        String trimmedName = requireName(name);
        String trimmedZone = blankToNull(zone);
        expire(discordGuildId, now);

        Instant popsAt = UtcTimeParser.nextOccurrence(popsAtUtc, now);
        requireFreeSlot(discordGuildId, trimmedName, trimmedZone, popsAt, NO_ROW);
        try {
            return objectives.saveAndFlush(new Objective(discordGuildId, trimmedName, trimmedZone, popsAt, callerId));
        } catch (DataIntegrityViolationException e) {
            // ux_objective_slot. Two people relaying the same intel at once both pass the
            // check above, and the second one lands here.
            throw new CommandException(duplicateMessage(trimmedName, trimmedZone, popsAt), e);
        }
    }

    /** The live list, soonest first, with anything past the grace window dropped. */
    @Transactional
    public List<Objective> list(long discordGuildId, Instant now) {
        expire(discordGuildId, now);
        return objectives.findByDiscordGuildIdOrderByPopsAtAsc(discordGuildId);
    }

    /**
     * Changes one line: its name, its time, its zone, or any combination of the three.
     *
     * <p>A new time is resolved against {@code now} the way {@link #add} resolves one, so
     * moving an objective to a time that has gone by today moves it to tomorrow rather
     * than into the past, where the next sweep would delete it and take the correction
     * with it.
     *
     * @throws CommandException if the selector matches no line or more than one, if
     *     nothing was asked for, or if the result would duplicate another line
     */
    @Transactional
    public Edited edit(long discordGuildId, Selector selector, Change change, Instant now) {
        if (change.isEmpty()) {
            throw new CommandException(
                    "Say what to change: `new_name:`, `new_time:` or `new_zone:` — "
                            + "any one of them, or all three.");
        }
        Objective objective = select(discordGuildId, selector, now);

        String previousName = objective.getName();
        String previousZone = objective.getZone();
        Instant previousPopsAt = objective.getPopsAt();

        String name = change.name() == null ? previousName : requireName(change.name());
        String zone = change.zone() == null ? previousZone : blankToNull(change.zone());
        Instant popsAt = change.popsAtUtc() == null
                ? previousPopsAt
                : UtcTimeParser.nextOccurrence(change.popsAtUtc(), now);

        if (name.equals(previousName) && Objects.equals(zone, previousZone) && popsAt.equals(previousPopsAt)) {
            throw new CommandException("**%s** is already on the list exactly like that — nothing to change."
                    .formatted(Formatting.escapeMarkdown(previousName)));
        }
        // Checked before the fields move, so an auto-flush cannot show the query the
        // half-applied row it is checking against. Its own id is excluded, or an edit that
        // leaves the name and the time alone would collide with the line it is editing.
        requireFreeSlot(discordGuildId, name, zone, popsAt, objective.getId());

        objective.setName(name);
        objective.setZone(zone);
        objective.setPopsAt(popsAt);
        try {
            objectives.saveAndFlush(objective);
        } catch (DataIntegrityViolationException e) {
            throw new CommandException(duplicateMessage(name, zone, popsAt), e);
        }
        return new Edited(objective, previousName, previousZone, previousPopsAt);
    }

    /**
     * Takes one line off the list.
     *
     * @return the objective as it was, so the reply can say what went
     * @throws CommandException if the selector matches no line or more than one
     */
    @Transactional
    public Objective remove(long discordGuildId, Selector selector, Instant now) {
        Objective objective = select(discordGuildId, selector, now);
        objectives.delete(objective);
        return objective;
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

    /**
     * The one live objective a selector names.
     *
     * <p>Matched in Java over {@link #list} rather than by a query of its own, so that the
     * sweep has already run: an expired row is not on the board, and neither editing nor
     * removing one should appear to work.
     */
    private Objective select(long discordGuildId, Selector selector, Instant now) {
        String name = requireName(selector.name());
        LocalTime popsAtUtc = selector.popsAtUtc();
        String zone = blankToNull(selector.zone());

        List<Objective> byName = list(discordGuildId, now).stream()
                .filter(objective -> objective.getName().equalsIgnoreCase(name))
                .toList();
        if (byName.isEmpty()) {
            throw new CommandException("Nothing called **%s** is on the list. `/objective show` has what is."
                    .formatted(Formatting.escapeMarkdown(name)));
        }

        List<Objective> matches = byName.stream()
                // A time of day, and not the resolved instant: an objective inside its
                // grace window popped today, and today's 20:00 is not what "20:00"
                // resolves to once 20:00 has gone. Typing the time straight off the board
                // has to find the line it was read from.
                .filter(objective -> popsAtUtc == null || objective.popsAtUtc().equals(popsAtUtc))
                .filter(objective -> zone == null || zone.equalsIgnoreCase(objective.getZone()))
                .toList();

        if (matches.isEmpty()) {
            throw new CommandException("**%s** is on the list, but not %s — it is up %s."
                    .formatted(Formatting.escapeMarkdown(name), narrowing(popsAtUtc, zone), slots(byName, now)));
        }
        if (matches.size() > 1) {
            throw new CommandException(("**%s** is on the list %d times — %s. Add `time:` or `zone:` "
                            + "to say which one you mean.")
                    .formatted(Formatting.escapeMarkdown(name), matches.size(), slots(matches, now)));
        }
        return matches.get(0);
    }

    private void requireFreeSlot(long discordGuildId, String name, String zone, Instant popsAt, long excludeId) {
        if (objectives.slotTaken(discordGuildId, name, zone == null ? "" : zone, popsAt, excludeId)) {
            throw new CommandException(duplicateMessage(name, zone, popsAt));
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new CommandException(
                    "Give the objective a name, for example "
                            + "`/objective add name:Fort Sterling chest time:20:00`.");
        }
        return trimmed;
    }

    /** Blank and absent are the same thing for a zone, and only one of them is stored. */
    private static String blankToNull(String zone) {
        if (zone == null) {
            return null;
        }
        String trimmed = zone.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String duplicateMessage(String name, String zone, Instant popsAt) {
        return ("**%s** is already on the list for %s. Add it at another time, or leave it — "
                        + "somebody has already called it.")
                .formatted(Formatting.escapeMarkdown(name), slot(zone, popsAt));
    }

    /** Where and when one line is, said the way the board says it: {@code 20:00 UTC in Martlock}. */
    public static String slot(Objective objective) {
        return slot(objective.getZone(), objective.getPopsAt());
    }

    private static String slot(String zone, Instant popsAt) {
        String time = "`%s` UTC".formatted(UtcTimeParser.timeOf(popsAt));
        return zone == null ? time : time + " in " + Formatting.escapeMarkdown(zone);
    }

    /**
     * The candidates a refusal lists, marking the ones that have already popped.
     *
     * <p>Not decoration. Two lines can share a name, a zone and a time of day for as long
     * as the grace window: today's, still up after popping, and tomorrow's, added since.
     * Nothing in the selector separates those, so the refusal has to at least say which
     * of them is which instead of printing the same slot twice.
     */
    private static String slots(List<Objective> candidates, Instant now) {
        String joined = candidates.stream()
                .limit(SLOTS_LISTED)
                .map(objective -> hasPopped(objective, now) ? slot(objective) + " (popped)" : slot(objective))
                .collect(Collectors.joining(", "));
        int rest = candidates.size() - Math.min(candidates.size(), SLOTS_LISTED);
        return rest == 0 ? joined : joined + ", and %d more".formatted(rest);
    }

    /** What the caller narrowed by, so a refusal can repeat it back to them. */
    private static String narrowing(LocalTime popsAtUtc, String zone) {
        StringBuilder out = new StringBuilder();
        if (popsAtUtc != null) {
            out.append("at `").append(popsAtUtc).append("` UTC");
        }
        if (zone != null) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append("in ").append(Formatting.escapeMarkdown(zone));
        }
        return out.toString();
    }
}
