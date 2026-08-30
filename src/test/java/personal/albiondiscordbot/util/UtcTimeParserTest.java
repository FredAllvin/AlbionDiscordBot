package personal.albiondiscordbot.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import personal.albiondiscordbot.discord.CommandException;

/**
 * Turning {@code HH:MM} into the moment it next happens.
 *
 * <p>This is the whole of {@code /objective}: everything downstream is a comparison
 * against the instant produced here, so a time resolved to the wrong day sorts wrongly
 * and expires wrongly, and the only symptom is a list that quietly says the wrong thing.
 */
class UtcTimeParserTest {

    /** 2026-08-30T12:00:00Z — a Sunday noon, so both directions have room either side. */
    private static final Instant NOON = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    @DisplayName("reads a two-digit time")
    void parsesTwoDigitTime() {
        assertThat(UtcTimeParser.parse("20:00")).isEqualTo(LocalTime.of(20, 0));
        assertThat(UtcTimeParser.parse("08:05")).isEqualTo(LocalTime.of(8, 5));
    }

    @Test
    @DisplayName("both ends of the day are valid")
    void parsesTheEdges() {
        assertThat(UtcTimeParser.parse("00:00")).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(UtcTimeParser.parse("23:59")).isEqualTo(LocalTime.of(23, 59));
    }

    @Test
    @DisplayName("surrounding whitespace is not a typo worth refusing")
    void trims() {
        assertThat(UtcTimeParser.parse("  20:00  ")).isEqualTo(LocalTime.of(20, 0));
    }

    @ParameterizedTest
    @DisplayName("anything that is not exactly HH:MM is refused")
    @ValueSource(strings = {"9:30", "8:5", "24:00", "23:60", "930", "2000", "20:00:00", "20.00", "abc"})
    void refusesEverythingElse(String input) {
        // The message has to show the shape, because "that is not a time" leaves someone
        // who typed 9:30 with no idea what was wrong with it.
        assertThatThrownBy(() -> UtcTimeParser.parse(input))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("00:00");
    }

    @Test
    @DisplayName("nothing at all is refused too")
    void refusesBlank() {
        assertThatThrownBy(() -> UtcTimeParser.parse("   ")).isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> UtcTimeParser.parse(null)).isInstanceOf(CommandException.class);
    }

    @Test
    @DisplayName("a time still ahead today is today")
    void resolvesForwardWithinTheDay() {
        assertThat(UtcTimeParser.nextOccurrence(LocalTime.of(20, 0), NOON))
                .isEqualTo(Instant.parse("2026-08-30T20:00:00Z"));
    }

    @Test
    @DisplayName("a time already gone by today is tomorrow's")
    void rollsOverToTomorrow() {
        assertThat(UtcTimeParser.nextOccurrence(LocalTime.of(10, 0), NOON))
                .isEqualTo(Instant.parse("2026-08-31T10:00:00Z"));
    }

    @Test
    @DisplayName("a time that is exactly now is now, not a day away")
    void resolvesTheExactMoment() {
        assertThat(UtcTimeParser.nextOccurrence(LocalTime.of(12, 0), NOON)).isEqualTo(NOON);
    }

    @Test
    @DisplayName("seconds past the minute put it on tomorrow")
    void secondsCountAsPast() {
        // Today's 12:00 has already been, by thirty seconds. "When it pops" is a future
        // tense, so the answer is tomorrow's rather than a moment in the past.
        Instant justAfter = Instant.parse("2026-08-30T12:00:30Z");
        assertThat(UtcTimeParser.nextOccurrence(LocalTime.of(12, 0), justAfter))
                .isEqualTo(Instant.parse("2026-08-31T12:00:00Z"));
    }

    @Test
    @DisplayName("late at night, an early time is a few hours off rather than a day")
    void crossesMidnight() {
        Instant lateNight = Instant.parse("2026-08-30T23:55:00Z");
        assertThat(UtcTimeParser.nextOccurrence(LocalTime.of(0, 10), lateNight))
                .isEqualTo(Instant.parse("2026-08-31T00:10:00Z"));
    }

    @Test
    @DisplayName("the reply knows when it has rolled over")
    void reportsTheNextDay() {
        assertThat(UtcTimeParser.isNextDay(Instant.parse("2026-08-31T10:00:00Z"), NOON))
                .isTrue();
        assertThat(UtcTimeParser.isNextDay(Instant.parse("2026-08-30T20:00:00Z"), NOON))
                .isFalse();
    }

    @Test
    @DisplayName("the grace window reads as a duration")
    void humanizes() {
        assertThat(UtcTimeParser.humanize(Duration.ofMinutes(30))).isEqualTo("30m");
        assertThat(UtcTimeParser.humanize(Duration.ofMinutes(60))).isEqualTo("1h");
        assertThat(UtcTimeParser.humanize(Duration.ofMinutes(90))).isEqualTo("1h 30m");
    }
}
