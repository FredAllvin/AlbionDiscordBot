package personal.albiondiscordbot.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import personal.albiondiscordbot.discord.CommandException;

class SilverAmountParserTest {

    @ParameterizedTest
    @CsvSource({
        "1000000, 1000000",
        "1m, 1000000",
        "1M, 1000000",
        "1.5m, 1500000",
        "500k, 500000",
        "500K, 500000",
        // "kk" is the Albion idiom for a million
        "1kk, 1000000",
        "2kk, 2000000",
        "1b, 1000000000",
        "'1,000,000', 1000000",
        "'1 000 000', 1000000",
        "1_000_000, 1000000",
        "'  2m  ', 2000000",
        "0.5k, 500",
        "1, 1"
    })
    @DisplayName("parses the forms players actually type")
    void parsesValidAmounts(String input, long expected) {
        assertThat(SilverAmountParser.parse(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "1x", "m", "1.2.3", "1m2", "$100", "1e6"})
    @DisplayName("rejects garbage with a usable message")
    void rejectsInvalid(String input) {
        assertThatThrownBy(() -> SilverAmountParser.parse(input))
                .isInstanceOf(CommandException.class);
    }

    @Test
    @DisplayName("rejects zero and negatives")
    void rejectsNonPositive() {
        assertThatThrownBy(() -> SilverAmountParser.parse("0"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("greater than zero");

        assertThatThrownBy(() -> SilverAmountParser.parse("-5m"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> SilverAmountParser.parse("0.0001k"))
                .isInstanceOf(CommandException.class);
    }

    @Test
    @DisplayName("rejects implausibly large amounts rather than overflowing")
    void rejectsAbsurdAmounts() {
        assertThatThrownBy(() -> SilverAmountParser.parse("9999b"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("typo");
    }

    @Test
    @DisplayName("fractional silver truncates rather than rounding up")
    void truncatesFractions() {
        assertThat(SilverAmountParser.parse("1.9999k")).isEqualTo(1999);
    }

    @Test
    @DisplayName("escapeHtml neutralises names that would otherwise inject script")
    void escapesHtml() {
        assertThat(Formatting.escapeHtml("<script>alert(1)</script>"))
                .doesNotContain("<script>")
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(Formatting.escapeHtml("Bob \"The\" O'Neil & Co"))
                .isEqualTo("Bob &quot;The&quot; O&#39;Neil &amp; Co");
    }

    @Test
    @DisplayName("silver formatting")
    void formatsSilver() {
        assertThat(Formatting.silver(1_500_000)).isEqualTo("1,500,000");
        assertThat(Formatting.compact(12_400_000)).isEqualTo("12.4m");
        assertThat(Formatting.compact(1_000_000)).isEqualTo("1m");
        assertThat(Formatting.compact(500)).isEqualTo("500");
    }
}
