package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import personal.albiondiscordbot.domain.TransactionType;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * A batch is reversed once even when several officers hit {@code /undo} together.
 *
 * <p>The "already been reversed" check is a read followed by a write. Under READ
 * COMMITTED a concurrent caller cannot see the other's uncommitted reversals, so before
 * the row lock every one of them passed the check and applied a full reversal — measured
 * at eight of eight, taking a member from 1,000,000 to -7,000,000, with the ledger
 * recording all eight as valid.
 *
 * <p>Two officers reacting to the same bad split is the realistic version of this.
 */
@SpringBootTest
class UndoConcurrencyTest extends PostgresTestBase {

    private static final long GUILD = 7002L;
    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final long OFFICER = 91L;

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

    private String splitOneMillionEach() {
        return batches.executeSplit(
                        GUILD,
                        List.of(
                                new BatchConfirmationService.Recipient(ALICE, "Alice", true),
                                new BatchConfirmationService.Recipient(BOB, "Bob", true)),
                        1_000_000L,
                        OFFICER,
                        "@Raiders",
                        UUID.randomUUID().toString())
                .batchId();
    }

    @Test
    @DisplayName("eight simultaneous undos reverse the batch exactly once")
    void concurrentUndoReversesOnce() throws Exception {
        String batchId = splitOneMillionEach();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger reversed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    balances.undoBatch(GUILD, batchId, OFFICER);
                    reversed.incrementAndGet();
                } catch (Exception expected) {
                    // Everyone after the first is refused, whether by the check or the index.
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(reversed.get()).as("exactly one caller may apply the reversal").isEqualTo(1);
        assertThat(balances.balanceOf(GUILD, ALICE)).isZero();
        assertThat(balances.balanceOf(GUILD, BOB)).isZero();

        long reversalRows = ledger.findByReference(batchId).stream()
                .filter(t -> t.getType() == TransactionType.REVERSAL)
                .count();
        assertThat(reversalRows).as("one reversal row per member, no more").isEqualTo(2);
    }

    @Test
    @DisplayName("a second undo afterwards is refused with a readable message")
    void sequentialUndoIsRefused() {
        String batchId = splitOneMillionEach();
        balances.undoBatch(GUILD, batchId, OFFICER);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> balances.undoBatch(GUILD, batchId, OFFICER))
                .hasMessageContaining("already been reversed");

        assertThat(balances.balanceOf(GUILD, ALICE)).isZero();
    }
}
