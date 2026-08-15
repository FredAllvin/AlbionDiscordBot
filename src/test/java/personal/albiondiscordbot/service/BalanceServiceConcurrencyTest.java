package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
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
 * The tests that actually protect the money.
 *
 * <p>These run against real Postgres rather than H2 specifically because they exercise
 * {@code ON CONFLICT} upserts and conditional updates — H2's Postgres compatibility
 * mode would happily pass while production lost updates.
 */
@SpringBootTest
class BalanceServiceConcurrencyTest extends PostgresTestBase {

    private static final long GUILD = 999L;
    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    @MockitoBean
    private JDA jda;

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private BalanceTransactionRepository ledger;

    @Autowired
    private DiscordGuildConfigRepository configRepository;

    @Autowired
    private personal.albiondiscordbot.service.GuildConfigService guildConfigService;

    /**
     * A confirmation token stands for one click on one preview, so tests that mean to
     * apply a batch mint a fresh one. Reusing a token is the double-click case, and
     * {@link BatchClaimTest} is where that is exercised deliberately.
     */
    private static String freshClaim() {
        return java.util.UUID.randomUUID().toString();
    }

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        balanceRepository.deleteAll();
        configRepository.deleteAll();
        guildConfigService.getOrCreate(GUILD);
    }

    @Test
    @DisplayName("concurrent credits sum exactly — no lost updates")
    void concurrentCreditsDoNotLoseUpdates() throws Exception {
        int threads = 8;
        int incrementsPerThread = 250;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < incrementsPerThread; i++) {
                        balanceService.add(GUILD, ALICE, 1L, ALICE, null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        long expected = (long) threads * incrementsPerThread;
        assertThat(balanceService.balanceOf(GUILD, ALICE)).isEqualTo(expected);
        // one ledger row per mutation, no more and no fewer
        assertThat(ledger.count()).isEqualTo(expected);
    }

    @Test
    @DisplayName("concurrent transfers neither create nor destroy silver")
    void concurrentTransfersConserveTotal() throws Exception {
        balanceService.add(GUILD, ALICE, 10_000L, ALICE, null);
        balanceService.add(GUILD, BOB, 10_000L, BOB, null);
        long totalBefore = balanceService.totalSilver(GUILD);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            // half send Alice -> Bob, half send Bob -> Alice, to provoke deadlocks if
            // the implementation were to lock rows in an inconsistent order
            boolean aliceToBob = t % 2 == 0;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < 100; i++) {
                        try {
                            if (aliceToBob) {
                                balanceService.give(GUILD, ALICE, BOB, 100L);
                            } else {
                                balanceService.give(GUILD, BOB, ALICE, 100L);
                            }
                        } catch (CommandException e) {
                            // running out of silver mid-run is expected and fine
                            rejected.incrementAndGet();
                        } catch (RuntimeException e) {
                            // A deadlock would surface here. Transfers must never
                            // deadlock, so record it and fail the assertion below.
                            deadlocks.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(deadlocks.get())
                .as("transfers must lock rows in a consistent order and never deadlock")
                .isZero();
        assertThat(balanceService.totalSilver(GUILD)).isEqualTo(totalBefore);
        assertThat(balanceService.balanceOf(GUILD, ALICE)).isNotNegative();
        assertThat(balanceService.balanceOf(GUILD, BOB)).isNotNegative();
    }

    @Test
    @DisplayName("a transfer with insufficient funds moves nothing")
    void insufficientFundsIsAtomic() {
        balanceService.add(GUILD, ALICE, 500L, ALICE, null);

        assertThatThrownBy(() -> balanceService.give(GUILD, ALICE, BOB, 1_000L))
                .isInstanceOf(CommandException.class);

        assertThat(balanceService.balanceOf(GUILD, ALICE)).isEqualTo(500L);
        assertThat(balanceService.balanceOf(GUILD, BOB)).isZero();
    }

    @Test
    @DisplayName("removing more than someone holds is refused outright")
    void cannotOverdrawViaRemove() {
        balanceService.add(GUILD, ALICE, 500L, ALICE, null);

        assertThatThrownBy(() -> balanceService.remove(GUILD, ALICE, 900L, BOB, null))
                .isInstanceOf(CommandException.class);

        assertThat(balanceService.balanceOf(GUILD, ALICE)).isEqualTo(500L);
    }

    @Test
    @DisplayName("a split credits the flat amount to EACH member, not a total divided")
    void splitIsFlatPerPerson() {
        Set<Long> members = new LinkedHashSet<>(Set.of(10L, 11L, 12L, 13L, 14L));

        BalanceService.SplitResult result =
                balanceService.creditSplit(GUILD, members, 1_000_000L, ALICE, "payout15", freshClaim());

        assertThat(result.recipientCount()).isEqualTo(5);
        assertThat(result.amountEach()).isEqualTo(1_000_000L);
        assertThat(result.totalCredited()).isEqualTo(5_000_000L);

        for (Long member : members) {
            assertThat(balanceService.balanceOf(GUILD, member)).isEqualTo(1_000_000L);
        }
        assertThat(ledger.findByReference(result.batchId())).hasSize(5);
    }

    @Test
    @DisplayName("a duplicated member is credited once, not twice")
    void splitDeduplicates() {
        // Set semantics make the Postgres "cannot affect row a second time" error
        // unreachable, and also stop a double mention paying someone twice.
        Set<Long> members = new LinkedHashSet<>();
        members.add(20L);
        members.add(21L);
        members.add(20L);

        BalanceService.SplitResult result =
                balanceService.creditSplit(GUILD, members, 1_000L, ALICE, "dupes", freshClaim());

        assertThat(result.recipientCount()).isEqualTo(2);
        assertThat(balanceService.balanceOf(GUILD, 20L)).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("reset zeroes the balance and records what was there")
    void resetRecordsPreviousAmount() {
        balanceService.add(GUILD, ALICE, 7_500L, ALICE, null);

        long previous = balanceService.reset(GUILD, ALICE, BOB);

        assertThat(previous).isEqualTo(7_500L);
        assertThat(balanceService.balanceOf(GUILD, ALICE)).isZero();
    }
}
