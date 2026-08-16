package personal.albiondiscordbot.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.discord.CommandException;

/**
 * What an officer actually has in the clipboard when a CTA broke into several killboards.
 *
 * <p>albionbb merges fights at {@code /battles/417352406,417352999,417353400}, so the
 * comma-separated list and the whole pasted URL are both real inputs, not conveniences.
 */
class BattleIdParserTest {

    @Test
    @DisplayName("a single id is still a single id")
    void singleId() {
        assertThat(BattleIdParser.parse("417352406")).containsExactly(417352406L);
        assertThat(BattleIdParser.parse("  417352406  ")).containsExactly(417352406L);
    }

    @Test
    @DisplayName("commas, spaces and semicolons all separate ids")
    void severalIds() {
        assertThat(BattleIdParser.parse("417352406,417352999,417353400"))
                .containsExactly(417352406L, 417352999L, 417353400L);
        assertThat(BattleIdParser.parse("417352406, 417352999 417353400"))
                .containsExactly(417352406L, 417352999L, 417353400L);
        assertThat(BattleIdParser.parse("417352406;417352999")).containsExactly(417352406L, 417352999L);
    }

    @Test
    @DisplayName("a pasted killboard link works, merged or not")
    void pastedUrl() {
        assertThat(BattleIdParser.parse("https://europe.albionbb.com/battles/417352406"))
                .containsExactly(417352406L);
        // The merged view is the whole reason the multi-id form exists.
        assertThat(BattleIdParser.parse("https://europe.albionbb.com/battles/417352406,417352999"))
                .containsExactly(417352406L, 417352999L);
        assertThat(BattleIdParser.parse("https://albiononline.com/killboard/battles/417352406/"))
                .containsExactly(417352406L);
        assertThat(BattleIdParser.parse("https://europe.albionbb.com/battles/417352406?tab=kills"))
                .containsExactly(417352406L);
    }

    @Test
    @DisplayName("naming the same fight twice pays nobody twice")
    void duplicatesCollapse() {
        assertThat(BattleIdParser.parse("417352406,417352999,417352406"))
                .containsExactly(417352406L, 417352999L);
    }

    @Test
    @DisplayName("order is kept, because the first id is the one the preview leads with")
    void orderIsKept() {
        assertThat(BattleIdParser.parse("417353400,417352406")).containsExactly(417353400L, 417352406L);
    }

    @Test
    @DisplayName("something that is not a battle id says so, and quotes what was typed")
    void rejectsGarbage() {
        assertThatThrownBy(() -> BattleIdParser.parse("the big one"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("not a battle id");

        assertThatThrownBy(() -> BattleIdParser.parse("417352406,oops"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("oops");

        assertThatThrownBy(() -> BattleIdParser.parse("-5")).isInstanceOf(CommandException.class);
        assertThatThrownBy(() -> BattleIdParser.parse("   ")).isInstanceOf(CommandException.class);
    }

    @Test
    @DisplayName("a paste accident is refused rather than turned into 500 API calls")
    void tooManyIds() {
        String tooMany = IntStream.rangeClosed(1, BattleIdParser.MAX_BATTLES + 1)
                .mapToObj(i -> String.valueOf(417352406L + i))
                .collect(Collectors.joining(","));

        assertThatThrownBy(() -> BattleIdParser.parse(tooMany))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining(String.valueOf(BattleIdParser.MAX_BATTLES));

        String justEnough = IntStream.rangeClosed(1, BattleIdParser.MAX_BATTLES)
                .mapToObj(i -> String.valueOf(417352406L + i))
                .collect(Collectors.joining(","));
        assertThat(BattleIdParser.parse(justEnough)).hasSize(BattleIdParser.MAX_BATTLES);
    }
}
