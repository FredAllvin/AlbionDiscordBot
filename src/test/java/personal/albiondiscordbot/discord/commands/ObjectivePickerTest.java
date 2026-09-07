package personal.albiondiscordbot.discord.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.domain.Objective;

/**
 * The {@code /objective edit} picker: what its rows say, and that Discord is actually told
 * to offer them.
 *
 * <p>Both halves fail silently in production if they are wrong. An over-long choice name
 * throws inside the autocomplete handler, where there is no message to report it in and
 * the caller just watches a box that never fills; and an option that reaches Discord
 * without {@code autocomplete: true} registers fine and simply never suggests anything.
 * Nothing else in the suite builds a command definition, so this is the only place either
 * would be caught before a deploy.
 */
class ObjectivePickerTest {

    private static final Instant NOON = Instant.parse("2026-08-30T12:00:00Z");

    private static Objective objective(String name, String zone, int hour, int minute) {
        return new Objective(
                1L, name, zone, Instant.parse("2026-08-30T%02d:%02d:00Z".formatted(hour, minute)), 2L);
    }

    @Test
    @DisplayName("a row reads the way the board does")
    void labelsARow() {
        assertThat(ObjectiveCommand.label(objective("Chest", "Fort Sterling", 20, 0), NOON))
                .isEqualTo("🕒 Chest · Fort Sterling · 20:00 UTC");
    }

    @Test
    @DisplayName("no zone leaves no gap")
    void labelsARowWithoutAZone() {
        assertThat(ObjectiveCommand.label(objective("Evening cta", null, 20, 0), NOON))
                .isEqualTo("🕒 Evening cta · 20:00 UTC");
    }

    @Test
    @DisplayName("one already inside its grace window is marked, the same red the board uses")
    void marksAPoppedRow() {
        assertThat(ObjectiveCommand.label(objective("Chest", null, 11, 45), NOON))
                .startsWith("🔴 ")
                .endsWith("· 11:45 UTC · popped");
    }

    @Test
    @DisplayName("the longest row Discord could be sent still fits, with the time intact")
    void fitsTheWorstCase() {
        // The column widths: name is 100 and zone is 60, so the two of them alone are
        // sixty characters past what a choice name may hold.
        String label = ObjectiveCommand.label(objective("c".repeat(100), "z".repeat(60), 11, 45), NOON);

        assertThat(label).hasSizeLessThanOrEqualTo(Command.Choice.MAX_NAME_LENGTH);
        // The name is what gives way, so the part that tells two lines apart survives.
        assertThat(label).endsWith("· 11:45 UTC · popped").contains("…");
        assertThatCode(() -> new Command.Choice(label, "id:1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("edit declares an autocompleting option and remove does not")
    void declaresTheAutocompletingOption() {
        List<SubcommandData> subcommands =
                new ObjectiveCommand(null, null).definition().getSubcommands();

        assertThat(options(subcommands, "edit"))
                .filteredOn(option -> "objective".equals(option.getName()))
                .singleElement()
                .satisfies(option -> assertThat(option.isAutoComplete()).isTrue());

        // remove names its line by hand, so nothing there autocompletes.
        assertThat(options(subcommands, "remove")).noneMatch(OptionData::isAutoComplete);
    }

    private static List<OptionData> options(List<SubcommandData> subcommands, String name) {
        return subcommands.stream()
                .filter(subcommand -> name.equals(subcommand.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /objective " + name + " subcommand"))
                .getOptions();
    }
}
