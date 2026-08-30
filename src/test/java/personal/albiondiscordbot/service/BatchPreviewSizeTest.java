package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;

/**
 * A preview of a real CTA has to be buildable at all.
 *
 * <p>Discord caps an embed field value at 1,024 characters and JDA throws rather than
 * truncate. The split table capped itself at {@code PREVIEW_ROWS} rows instead, and 25
 * rows of {@code name / balance / new balance} come to 1,407 — so every
 * {@code /split-cta} big enough to be worth running died inside its own preview, before
 * any silver moved, and the officer got "Something went wrong running that command".
 * Nineteen recipients was enough to trip it.
 *
 * <p>The suite missed it because every existing preview test uses three recipients. So
 * the fixtures here are the size of the fights this actually runs on: battle 418975628 is
 * the 368-player CTA of 17 August 2026, in which 34 Dumbo Elephants fought — 34 being the
 * number that matters, since a split credits our side, not the whole killboard.
 *
 * <p>No Postgres: {@code previewSplit} reads balances and nothing else, so the balance
 * lookup is stubbed and the test runs anywhere.
 */
class BatchPreviewSizeTest {

    /** Registered members of ours in battle 418975628. Comfortably past the old ceiling. */
    private static final int CTA_ROSTER = 34;

    private static final long GUILD = 1234567890L;
    private static final long BALANCE = 4_250_000L;
    private static final long EACH = 1_000_000L;

    private BatchConfirmationService batches;

    @BeforeEach
    void setUp() {
        BalanceService balances = mock(BalanceService.class);
        when(balances.balanceOf(anyLong(), anyLong())).thenReturn(BALANCE);
        batches = new BatchConfirmationService(balances, null, null, null, null);
    }

    private static List<Recipient> roster(int size) {
        List<Recipient> recipients = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            // 20 characters is what the table column allows, so these are worst-case wide.
            recipients.add(new Recipient(1000L + i, "Fighter" + i + "XXXXXXXXXXXXX", true));
        }
        return recipients;
    }

    private static String field(MessageEmbed embed, String name) {
        return embed.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private String splitTable(int size) {
        return field(
                batches.previewSplit(GUILD, roster(size), EACH, "the 368-player fight `418975628`"),
                "Balances after this split");
    }

    @Test
    @DisplayName("a CTA-sized split can be previewed at all")
    void ctaSizedSplitPreviewBuilds() {
        assertThat(splitTable(CTA_ROSTER)).isNotEmpty();
    }

    @Test
    @DisplayName("the split table never exceeds what Discord accepts in a field")
    void splitTableStaysInsideTheFieldLimit() {
        // Every size across the old cliff, plus a whole zerg, plus absurdity.
        for (int size : new int[] {1, 2, 17, 18, 19, 20, 24, 25, 26, CTA_ROSTER, 368, 5000}) {
            assertThat(splitTable(size).length())
                    .as("split table for %d recipients", size)
                    .isLessThanOrEqualTo(MessageEmbed.VALUE_MAX_LENGTH);
        }
    }

    @Test
    @DisplayName("the cashout table never exceeds it either")
    void cashoutTableStaysInsideTheFieldLimit() {
        for (int size : new int[] {1, 25, CTA_ROSTER, 368}) {
            String table = field(
                    batches.previewCashout(GUILD, roster(size), "@payout15"),
                    "Send these amounts in game");
            assertThat(table.length())
                    .as("cashout table for %d recipients", size)
                    .isLessThanOrEqualTo(MessageEmbed.VALUE_MAX_LENGTH);
        }
    }

    @Test
    @DisplayName("a truncated table says how many it left out, counting from what it showed")
    void tailCountsTheRowsActuallyOmitted() {
        String table = splitTable(368);

        long listed = table.lines().filter(line -> line.startsWith("Fighter")).count();
        assertThat(listed).isPositive();
        assertThat(table).contains("... and %d more".formatted(368 - listed));
    }

    @Test
    @DisplayName("a roster that fits is listed in full, with no tail")
    void shortRosterIsNotTruncated() {
        String table = splitTable(3);

        assertThat(table.lines().filter(line -> line.startsWith("Fighter")).count()).isEqualTo(3);
        assertThat(table).doesNotContain("... and");
    }

    @Test
    @DisplayName("the table is the same width wherever it is built")
    void rowsDoNotDependOnThePlatformLineSeparator() {
        // Built on Windows, run on Linux: %n would be two characters here and one there,
        // which is enough to move where the field overflows.
        assertThat(splitTable(2)).doesNotContain("\r");
    }
}
