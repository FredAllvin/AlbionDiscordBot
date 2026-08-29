package personal.albiondiscordbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check that stops {@code /balance add} paying a subset of the people it was given.
 *
 * <p>JDA resolves the mentions in a STRING option only against the map Discord sends with
 * the interaction, so mention text Discord did not resolve itself comes back as nothing at
 * all. These assert on the comparison that notices, because the failure it prevents is
 * silent: money reaches four of five names and the reply says "4 members" without ever
 * mentioning that a fifth was typed.
 */
class MentionParserTest {

    @Test
    @DisplayName("reads every mention that was typed, in order")
    void readsMentionsInOrder() {
        assertThat(MentionParser.userIds("<@111> <@222> <@333>"))
                .containsExactly("111", "222", "333");
    }

    @Test
    @DisplayName("reads the older nickname form too")
    void readsNicknameMentions() {
        assertThat(MentionParser.userIds("<@!111> and <@222>")).containsExactly("111", "222");
    }

    @Test
    @DisplayName("the same person twice counts once")
    void deduplicates() {
        assertThat(MentionParser.userIds("<@111> <@111>")).containsExactly("111");
    }

    @Test
    @DisplayName("text that only looks like a mention is not one")
    void ignoresNearMisses() {
        assertThat(MentionParser.userIds("@someone <@> <@abc> #111 <@&444>")).isEmpty();
    }

    @Test
    @DisplayName("nothing is missing when every mention resolved")
    void allResolved() {
        assertThat(MentionParser.unresolved("<@111> <@222>", List.of("111", "222")))
                .isEmpty();
    }

    @Test
    @DisplayName("names the mention Discord did not resolve — the whole point")
    void reportsTheUnresolvedOne() {
        // The shape of a pasted mention: it matches the pattern and renders in the
        // channel, but Discord never put it in the interaction's resolved map.
        assertThat(MentionParser.unresolved("<@111> <@222> <@333>", List.of("111", "333")))
                .containsExactly("222");
    }

    @Test
    @DisplayName("a role mention resolves nobody and is not mistaken for a member")
    void roleMentionIsNotAUser() {
        assertThat(MentionParser.unresolved("<@&999>", List.of())).isEmpty();
    }

    @Test
    @DisplayName("an id too long to be a snowflake is still reported, not dropped")
    void oversizedIdIsReportedRatherThanParsed() {
        String tooLong = "12345678901234567890123456789";
        assertThat(MentionParser.unresolved("<@" + tooLong + ">", List.of()))
                .containsExactly(tooLong);
    }

    @Test
    @DisplayName("nothing mentioned means nothing missing")
    void emptyInput() {
        assertThat(MentionParser.unresolved("", List.of())).isEmpty();
        assertThat(MentionParser.userIds(null)).isEmpty();
    }
}
