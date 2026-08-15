package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * One confirmation moves silver once, however many times it is clicked.
 *
 * <p>The buttons are stateless so they survive a restart, which means nothing in memory
 * can remember that a batch was applied. Before the claim token, two clicks on one
 * Confirm credited the whole role twice under two different batch ids — so {@code /undo}
 * on the id printed in the message reversed one of them and left the other looking
 * legitimate. Measured at the time: 1,000,000 each became 2,000,000 each.
 */
@SpringBootTest
class BatchClaimTest extends PostgresTestBase {

    private static final long GUILD = 7001L;
    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final long OFFICER = 90L;

    @MockitoBean
    private JDA jda;

    @Autowired
    private BalanceService balances;

    @Autowired
    private BatchConfirmationService batches;

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

    private List<BatchConfirmationService.Recipient> recipients() {
        return List.of(
                new BatchConfirmationService.Recipient(ALICE, "Alice", true),
                new BatchConfirmationService.Recipient(BOB, "Bob", true));
    }

    @Test
    @DisplayName("clicking Confirm twice credits the split once")
    void doubleConfirmCreditsOnce() {
        String oneClick = UUID.randomUUID().toString();

        batches.executeSplit(GUILD, recipients(), 1_000_000L, OFFICER, "@Raiders", oneClick);

        assertThatThrownBy(() ->
                        batches.executeSplit(GUILD, recipients(), 1_000_000L, OFFICER, "@Raiders", oneClick))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("already been used");

        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(1_000_000L);
        assertThat(balances.balanceOf(GUILD, BOB)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("a refused second confirm moves nothing at all")
    void refusedConfirmIsNotPartiallyApplied() {
        balances.add(GUILD, ALICE, 1_000_000L, OFFICER, "owed from a CTA");
        balances.add(GUILD, BOB, 1_000_000L, OFFICER, "owed from a CTA");

        String oneClick = UUID.randomUUID().toString();
        batches.executeCashout(GUILD, recipients(), OFFICER, "@Raiders", oneClick);

        balances.add(GUILD, ALICE, 500_000L, OFFICER, "earned after the cashout");

        assertThatThrownBy(() -> batches.executeCashout(GUILD, recipients(), OFFICER, "@Raiders", oneClick))
                .isInstanceOf(CommandException.class);

        // The silver earned after the first cashout must still be there: a refused claim
        // has to abort before resetToZero, not after it.
        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("two clicks landing at the same instant still credit once")
    void concurrentConfirmsCreditOnce() throws Exception {
        String oneClick = UUID.randomUUID().toString();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    batches.executeSplit(GUILD, recipients(), 1_000_000L, OFFICER, "@Raiders", oneClick);
                    applied.incrementAndGet();
                } catch (Exception expected) {
                    // Every loser of the race lands here.
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(applied.get()).isEqualTo(1);
        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("repeating a split on purpose still works — it is a different confirmation")
    void deliberateRepeatIsAllowed() {
        batches.executeSplit(GUILD, recipients(), 1_000_000L, OFFICER, "@Raiders", UUID.randomUUID().toString());
        batches.executeSplit(GUILD, recipients(), 1_000_000L, OFFICER, "@Raiders", UUID.randomUUID().toString());

        // Same amount, same people, same officer: only the confirmation differs, and two
        // real CTAs in one evening look exactly like this.
        assertThat(balances.balanceOf(GUILD, ALICE)).isEqualTo(2_000_000L);
    }
}
