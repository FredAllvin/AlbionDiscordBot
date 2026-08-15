package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.BalanceTransaction;
import personal.albiondiscordbot.domain.TransactionType;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * {@code /balance add @a @b @c <amount>} and its inverse.
 *
 * <p>The amount is per member, not a pot to divide, so these assert on what each member
 * ends up holding rather than on a total. The debit is all-or-nothing: an officer
 * correcting a mistaken split across eight people must not end up with four of them
 * corrected and no record of which four.
 */
@SpringBootTest
class BulkAdjustmentTest extends PostgresTestBase {

    private static final long GUILD = 7002L;
    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final long CARL = 3L;
    private static final long OFFICER = 90L;

    @MockitoBean
    private JDA jda;

    @Autowired
    private BalanceService balances;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private BalanceTransactionRepository ledger;

    @Autowired
    private DiscordGuildConfigRepository configRepository;

    @Autowired
    private GuildConfigService guildConfigService;

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        balanceRepository.deleteAll();
        configRepository.deleteAll();
        guildConfigService.getOrCreate(GUILD);
    }

    private List<BalanceTransaction> historyOf(long userId) {
        return ledger.findByDiscordGuildIdAndDiscordUserIdOrderByCreatedAtDesc(
                GUILD, userId, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("every mention gets the full amount, not a share of it")
    void creditsEachMentionInFull() {
        Map<Long, Long> after =
                balances.addEach(GUILD, List.of(ALICE, BOB, CARL), 1_000_000L, OFFICER, "hellgate");

        assertThat(after).containsOnlyKeys(ALICE, BOB, CARL).containsValues(1_000_000L);
        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(1_000_000L);
        assertThat(balances.balanceOf(GUILD, BOB)).isEqualTo(1_000_000L);
        assertThat(balances.balanceOf(GUILD, CARL)).isEqualTo(1_000_000L);
        assertThat(balances.totalSilver(GUILD)).isEqualTo(3_000_000L);

        assertThat(historyOf(ALICE))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getType()).isEqualTo(TransactionType.ADD);
                    assertThat(entry.getDelta()).isEqualTo(1_000_000L);
                    assertThat(entry.getNote()).isEqualTo("hellgate");
                });
    }

    @Test
    @DisplayName("mentioning the same member twice pays them once")
    void ignoresDuplicateMentions() {
        balances.addEach(GUILD, List.of(ALICE, ALICE, BOB), 1_000_000L, OFFICER, null);

        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(1_000_000L);
        assertThat(balances.balanceOf(GUILD, BOB)).isEqualTo(1_000_000L);
        assertThat(historyOf(ALICE)).hasSize(1);
    }

    @Test
    @DisplayName("a bulk debit takes the same amount from each member")
    void debitsEachMention() {
        balances.addEach(GUILD, List.of(ALICE, BOB), 2_000_000L, OFFICER, null);

        balances.removeEach(GUILD, List.of(ALICE, BOB), 500_000L, OFFICER, "wrong split");

        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(1_500_000L);
        assertThat(balances.balanceOf(GUILD, BOB)).isEqualTo(1_500_000L);
        assertThat(historyOf(BOB))
                .first()
                .satisfies(entry -> {
                    assertThat(entry.getType()).isEqualTo(TransactionType.REMOVE);
                    assertThat(entry.getDelta()).isEqualTo(-500_000L);
                });
    }

    @Test
    @DisplayName("one member who cannot cover it refuses the whole debit")
    void oneShortMemberRollsBackEverybody() {
        balances.addEach(GUILD, List.of(ALICE, CARL), 2_000_000L, OFFICER, null);
        balances.add(GUILD, BOB, 100_000L, OFFICER, null);

        assertThatThrownBy(() ->
                        balances.removeEach(GUILD, List.of(ALICE, BOB, CARL), 1_000_000L, OFFICER, "clawback"))
                .isInstanceOf(CommandException.class)
                // Names who was short, and says nothing moved — the officer needs both.
                .hasMessageContaining("<@" + BOB + ">")
                .hasMessageContaining("Nothing was taken");

        // Alice and Carl were debited successfully inside the transaction — only the
        // rollback puts them back, so these are what prove it happened.
        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(2_000_000L);
        assertThat(balances.balanceOf(GUILD, CARL)).isEqualTo(2_000_000L);
        assertThat(balances.balanceOf(GUILD, BOB)).isEqualTo(100_000L);

        assertThat(historyOf(ALICE))
                .noneMatch(entry -> entry.getType() == TransactionType.REMOVE);
    }
}
