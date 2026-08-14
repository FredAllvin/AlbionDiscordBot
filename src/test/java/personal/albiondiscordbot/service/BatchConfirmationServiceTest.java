package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;
import personal.albiondiscordbot.support.PostgresTestBase;

/** The confirm-before-paying flow: nothing moves until Confirm, and ids stay in budget. */
@SpringBootTest
class BatchConfirmationServiceTest extends PostgresTestBase {

    private static final long GUILD = 7070L;
    private static final long OFFICER = 1L;

    @MockitoBean
    private JDA jda;

    @Autowired
    private BatchConfirmationService batches;

    @Autowired
    private BalanceService balances;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private BalanceTransactionRepository ledger;

    @Autowired
    private DiscordGuildConfigRepository configs;

    @Autowired
    private GuildConfigService guildConfigService;

    private List<Recipient> recipients;

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        balanceRepository.deleteAll();
        configs.deleteAll();
        guildConfigService.getOrCreate(GUILD);

        recipients = List.of(
                new Recipient(10L, "Bogul", true),
                new Recipient(11L, "Zvz", true),
                new Recipient(12L, "SomeDiscordName", false));
    }

    @Test
    @DisplayName("building a preview moves no silver")
    void previewIsReadOnly() {
        balances.add(GUILD, 10L, 500_000L, OFFICER, null);

        batches.previewSplit(GUILD, recipients, 1_000_000L, "@payout15");

        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(500_000L);
        assertThat(balances.balanceOf(GUILD, 11L)).isZero();
        assertThat(balances.totalSilver(GUILD)).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("the preview shows the resulting balance, not just the payment")
    void previewShowsProjectedBalances() {
        balances.add(GUILD, 10L, 500_000L, OFFICER, null);

        String rendered = batches.previewSplit(GUILD, recipients, 1_000_000L, "@payout15").getFields().stream()
                .filter(f -> "Balances after this split".equals(f.getName()))
                .findFirst()
                .orElseThrow()
                .getValue();

        // 500,000 now becomes 1,500,000
        assertThat(rendered).contains("500,000").contains("1,500,000");
    }

    @Test
    @DisplayName("executing a split credits the flat amount to each recipient")
    void executeCreditsEach() {
        BalanceService.SplitResult result =
                batches.executeSplit(GUILD, recipients, 1_000_000L, OFFICER, "@payout15");

        assertThat(result.recipientCount()).isEqualTo(3);
        assertThat(result.totalCredited()).isEqualTo(3_000_000L);
        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(1_000_000L);
        assertThat(balances.balanceOf(GUILD, 12L)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("a cashout clears balances and records what was handed over")
    void cashoutClearsBalances() {
        batches.executeSplit(GUILD, recipients, 1_000_000L, OFFICER, "@payout15");
        balances.add(GUILD, 10L, 500_000L, OFFICER, "earlier split");

        BalanceService.CashoutResult result =
                batches.executeCashout(GUILD, recipients, OFFICER, "@payout15");

        assertThat(result.paidCount()).isEqualTo(3);
        assertThat(result.totalPaid()).isEqualTo(3_500_000L);
        assertThat(result.paid()).containsEntry(10L, 1_500_000L);

        // Silver has left the ledger — the guild no longer owes any of it.
        assertThat(balances.balanceOf(GUILD, 10L)).isZero();
        assertThat(balances.totalSilver(GUILD)).isZero();
    }

    @Test
    @DisplayName("a cashout skips anyone owed nothing instead of writing empty entries")
    void cashoutSkipsZeroBalances() {
        balances.add(GUILD, 10L, 1_000_000L, OFFICER, null);

        BalanceService.CashoutResult result =
                batches.executeCashout(GUILD, recipients, OFFICER, "@payout15");

        assertThat(result.paidCount()).isEqualTo(1);
        assertThat(result.skipped()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(result.totalPaid()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("the whole ZvZ flow: split into a role, extra silver, then cash one person out")
    void endToEndSingleMemberCashout() {
        // 1. ZvZ happens, everyone in the role gets a split
        batches.executeSplit(GUILD, recipients, 6_000_000L, OFFICER, "@zvz-2026-08-14");
        // 2. that member picks up silver from elsewhere too
        balances.add(GUILD, 10L, 4_000_000L, OFFICER, "hellgate loot");

        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(10_000_000L);

        // 3. cash out that one member — the amount is their balance, not a parameter
        List<Recipient> justThem = List.of(recipients.get(0));
        assertThat(batches.totalOwed(GUILD, justThem)).isEqualTo(10_000_000L);

        BalanceService.CashoutResult result =
                batches.executeCashout(GUILD, justThem, OFFICER, "<@10>");

        assertThat(result.paidCount()).isEqualTo(1);
        assertThat(result.totalPaid()).isEqualTo(10_000_000L);
        assertThat(balances.balanceOf(GUILD, 10L)).isZero();
        // everyone else is untouched by a single-member cashout
        assertThat(balances.balanceOf(GUILD, 11L)).isEqualTo(6_000_000L);
    }

    @Test
    @DisplayName("a settled member is owed nothing, so there is nothing to preview")
    void settledMemberOwedNothing() {
        List<Recipient> justThem = List.of(recipients.get(0));

        assertThat(batches.totalOwed(GUILD, justThem)).isZero();
    }

    @Test
    @DisplayName("a negative balance counts as zero owed, never as money to hand over")
    void debtIsNotOwed() {
        // The way someone actually ends up in debt: a split they spent, then reversed.
        BalanceService.SplitResult split =
                batches.executeSplit(GUILD, recipients, 1_000_000L, OFFICER, "@zvz");
        balances.remove(GUILD, 10L, 1_000_000L, OFFICER, "spent it");
        balances.undoBatch(GUILD, split.batchId(), OFFICER);

        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(-1_000_000L);

        // A debt must never be read as silver to hand over, and must not cancel out
        // someone else's positive balance in the same batch.
        assertThat(batches.totalOwed(GUILD, List.of(recipients.get(0)))).isZero();
        assertThat(batches.copyList(GUILD, List.of(recipients.get(0))))
                .isEqualTo("Nobody is owed anything.");
    }

    @Test
    @DisplayName("a cashout preview moves nothing")
    void cashoutPreviewIsReadOnly() {
        balances.add(GUILD, 10L, 1_000_000L, OFFICER, null);

        batches.previewCashout(GUILD, recipients, "@payout15");

        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("the copy list gives character name and full balance owed, not one split's share")
    void copyListUsesFullBalance() {
        balances.add(GUILD, 10L, 500_000L, OFFICER, "earlier split");
        batches.executeSplit(GUILD, recipients, 1_000_000L, OFFICER, "@payout15");

        String list = batches.copyList(GUILD, recipients);

        // This is what gets typed in game, so it must be the total owed: Bogul is 1.5m.
        assertThat(list).contains("Bogul\t1500000");
        assertThat(list).contains("Zvz\t1000000");
        // falls back to the Discord name when no character is registered
        assertThat(list).contains("SomeDiscordName\t1000000");
        assertThat(list).contains("Total to send: 3,500,000");
    }

    @Test
    @DisplayName("the copy list leaves out anyone owed nothing")
    void copyListSkipsZeroBalances() {
        balances.add(GUILD, 10L, 1_000_000L, OFFICER, null);

        String list = batches.copyList(GUILD, recipients);

        assertThat(list).contains("Bogul\t1000000");
        assertThat(list).doesNotContain("Zvz");
        assertThat(list).doesNotContain("SomeDiscordName");
    }

    @Test
    @DisplayName("button ids stay inside Discord's 100 character limit")
    void buttonIdsFitDiscordsLimit() {
        // Worst case: two 19-digit snowflakes plus the largest amount the parser accepts.
        for (String op : List.of(BatchConfirmationService.OP_SPLIT, BatchConfirmationService.OP_CASHOUT)) {
            List<Button> buttons = batches.buttons(
                    op,
                    BatchConfirmationService.SOURCE_ROLE,
                    "1234567890123456789",
                    personal.albiondiscordbot.util.SilverAmountParser.MAX_AMOUNT,
                    9223372036854775807L);

            assertThat(buttons).hasSize(3);
            for (Button button : buttons) {
                assertThat(button.getCustomId())
                        .as("custom id %s", button.getCustomId())
                        .hasSizeLessThanOrEqualTo(100);
            }
        }
    }

    @Test
    @DisplayName("confirm, deny and copy are distinct, and encode which operation they are")
    void buttonsAreDistinct() {
        List<Button> split = batches.buttons(
                BatchConfirmationService.OP_SPLIT, BatchConfirmationService.SOURCE_ROLE, "123", 1_000L, 456L);

        assertThat(split).extracting(Button::getCustomId)
                .containsExactly(
                        "bt:ok:split:r:123:1000:456",
                        "bt:no:split:r:123:1000:456",
                        "bt:cp:split:r:123:1000:456");

        // A cashout always clears the whole balance, so it encodes no amount.
        List<Button> cashout = batches.buttons(
                BatchConfirmationService.OP_CASHOUT, BatchConfirmationService.SOURCE_ROLE, "123", 0L, 456L);

        assertThat(cashout.get(0).getCustomId()).isEqualTo("bt:ok:cash:r:123:0:456");
        assertThat(cashout.get(0).getLabel()).isEqualTo("Sent — clear balances");
    }
}
