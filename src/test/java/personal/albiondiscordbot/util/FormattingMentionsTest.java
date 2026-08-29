package personal.albiondiscordbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ping line under a split announcement.
 *
 * <p>A CTA can credit more people than a 2000-character message holds, so the list is cut
 * — but everyone was still credited, and a line that silently stopped short would read as
 * "these people got paid and you did not".
 */
class FormattingMentionsTest {

    @Test
    @DisplayName("a short list is every mention and no tail")
    void listsEveryone() {
        assertThat(Formatting.mentions(List.of(1L, 2L, 3L), 1800))
                .isEqualTo("<@1> <@2> <@3>");
    }

    @Test
    @DisplayName("nobody to ping is an empty line, not a stray tail")
    void emptyList() {
        assertThat(Formatting.mentions(List.of(), 1800)).isEmpty();
    }

    @Test
    @DisplayName("a list too long to fit is cut and says how many it left out")
    void truncatesAndCounts() {
        List<Long> everyone = LongStream.rangeClosed(1, 200)
                .map(i -> 100000000000000000L + i)
                .boxed()
                .toList();

        String line = Formatting.mentions(everyone, 1800);

        assertThat(line).hasSizeLessThanOrEqualTo(1800);
        assertThat(line).contains("<@100000000000000001>");
        assertThat(line).containsPattern("…and \\d+ more$");

        // The count has to be the real remainder, or it is worse than no count at all.
        int listed = line.split("<@").length - 1;
        assertThat(line).endsWith("…and " + (200 - listed) + " more");
    }

    @Test
    @DisplayName("a budget too small for even one mention still reports the whole count")
    void budgetSmallerThanOneMention() {
        assertThat(Formatting.mentions(List.of(1L, 2L, 3L), 10)).isEqualTo("…and 3 more");
    }
}
