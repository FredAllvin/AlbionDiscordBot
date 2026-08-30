package personal.albiondiscordbot.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import personal.albiondiscordbot.discord.CommandException;

/**
 * Parses a {@code HH:MM} UTC time of day and resolves it to the moment it next happens.
 *
 * <p>Exactly two digits either side of the colon, which is what the guild reads off the
 * in-game clock. {@code 9:30} and {@code 930} are refused rather than guessed at: a
 * misread objective time sends the whole group to an empty map at the wrong hour, and
 * that is a worse outcome than being told to type it again.
 *
 * <p>UTC throughout, and deliberately so — Albion's timers are published in UTC, the
 * guild spans timezones, and UTC has no daylight saving to move an objective by an hour
 * twice a year.
 */
public final class UtcTimeParser {

    /** {@code 00:00}–{@code 23:59}. Two digits each side, no other shape allowed. */
    private static final Pattern HH_MM = Pattern.compile("([01][0-9]|2[0-3]):([0-5][0-9])");

    private UtcTimeParser() {
    }

    /**
     * @return the time of day, UTC
     * @throws CommandException with a user-facing message if the input is unusable
     */
    public static LocalTime parse(String input) {
        if (input == null || input.isBlank()) {
            throw new CommandException("Enter a UTC time, for example `20:00`.");
        }
        Matcher matcher = HH_MM.matcher(input.trim());
        if (!matcher.matches()) {
            throw new CommandException(
                    ("`%s` is not a UTC time. Use two digits either side of the colon, "
                                    + "24-hour: `00:00` through `23:59` — so `08:05`, not `8:5`.")
                            .formatted(input));
        }
        return LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /**
     * The next time it is {@code time} in UTC, at or after {@code now}.
     *
     * <p>{@code HH:MM} names a time of day and not a date, so this is what turns it into
     * an event. A time that has already gone by today belongs to tomorrow: someone
     * typing 10:00 at noon is telling you about the next one, not the one they missed.
     * That also caps an objective at 24 hours out, which is as far ahead as the input can
     * express.
     */
    public static Instant nextOccurrence(LocalTime time, Instant now) {
        ZonedDateTime candidate = now.atZone(ZoneOffset.UTC).with(time);
        // Equal counts as still to come: 12:00 added at exactly 12:00:00 means this
        // minute, not a day from now. Sub-minute precision cuts the other way, since a
        // candidate's seconds are always zero — at 12:00:30, today's 12:00 has been.
        if (!candidate.toInstant().isBefore(now)) {
            return candidate.toInstant();
        }
        return candidate.plusDays(1).toInstant();
    }

    /**
     * Whether {@code instant} lands on a later UTC day than {@code now} does.
     *
     * <p>Only so the reply can say so out loud. The Discord timestamp beside it carries
     * the date, but "tomorrow" is the part someone re-reading their own typo needs to
     * catch, and it is the part a skim misses.
     */
    public static boolean isNextDay(Instant instant, Instant now) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().isAfter(now.atZone(ZoneOffset.UTC).toLocalDate());
    }

    /** {@code 90 minutes -> "1h 30m"}, for a window measured in minutes. */
    public static String humanize(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + "h" : "%dh %dm".formatted(hours, rest);
    }
}
