package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.TransactionType;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/** Reversing a batch must move exactly what it moved originally — no more, no less. */
@SpringBootTest
class UndoBatchTest extends PostgresTestBase {

    private static final long GUILD = 4242L;
    private static final long OTHER_GUILD = 4343L;
    private static final long OFFICER = 1L;

    @MockitoBean
    private JDA jda;

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

    private Set<Long> members;

    /** One token stands for one click on one preview. */
    private static String freshClaim() {
        return java.util.UUID.randomUUID().toString();
    }

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        balanceRepository.deleteAll();
        configs.deleteAll();
        guildConfigService.getOrCreate(GUILD);
        guildConfigService.getOrCreate(OTHER_GUILD);
        members = new LinkedHashSet<>(Set.of(10L, 11L, 12L));
    }

    @Test
    @DisplayName("reversing a split returns every balance to exactly where it started")
    void undoRestoresBalances() {
        balances.add(GUILD, 10L, 5_000_000L, OFFICER, "pre-existing");
        long totalBefore = balances.totalSilver(GUILD);

        BalanceService.SplitResult split = balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());
        assertThat(balances.totalSilver(GUILD)).isEqualTo(totalBefore + 3_000_000L);

        BalanceService.UndoResult undo = balances.undoBatch(GUILD, split.batchId(), OFFICER);

        assertThat(undo.reversedCount()).isEqualTo(3);
        assertThat(undo.totalMoved()).isEqualTo(3_000_000L);
        assertThat(undo.wentNegative()).isEmpty();
        assertThat(balances.totalSilver(GUILD)).isEqualTo(totalBefore);
        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(5_000_000L);
        assertThat(balances.balanceOf(GUILD, 11L)).isZero();
    }

    @Test
    @DisplayName("a split can only be reversed once")
    void undoIsNotRepeatable() {
        BalanceService.SplitResult split = balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());
        balances.undoBatch(GUILD, split.batchId(), OFFICER);

        // Without this guard a second undo would debit everyone all over again.
        assertThatThrownBy(() -> balances.undoBatch(GUILD, split.batchId(), OFFICER))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("already been reversed");

        assertThat(balances.balanceOf(GUILD, 10L)).isZero();
    }

    @Test
    @DisplayName("someone who already spent it goes negative rather than under-reversing")
    void spentSilverGoesNegative() {
        BalanceService.SplitResult split = balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());
        // member 10 spends most of the erroneous split before anyone notices
        balances.remove(GUILD, 10L, 900_000L, OFFICER, "spent it");

        BalanceService.UndoResult undo = balances.undoBatch(GUILD, split.batchId(), OFFICER);

        // The full amount is still reclaimed; the shortfall shows as a debt, which keeps
        // the ledger honest instead of silently gifting them 900k.
        assertThat(undo.totalMoved()).isEqualTo(3_000_000L);
        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(-900_000L);
        assertThat(undo.wentNegative()).containsExactly(10L);
    }

    @Test
    @DisplayName("an unknown batch id is refused")
    void unknownBatchRejected() {
        assertThatThrownBy(() -> balances.undoBatch(GUILD, "not-a-real-batch", OFFICER))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("No batch found");
    }

    @Test
    @DisplayName("one server cannot reverse another server's batch")
    void cannotReverseAnotherGuildsBatch() {
        BalanceService.SplitResult split = balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());

        assertThatThrownBy(() -> balances.undoBatch(OTHER_GUILD, split.batchId(), OFFICER))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("No batch found");

        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("reversing a cashout puts the silver back on the books")
    void undoCashoutRestoresBalances() {
        balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());
        BalanceService.CashoutResult cashout = balances.cashOut(GUILD, members, OFFICER, "@payout15", freshClaim());

        assertThat(balances.totalSilver(GUILD)).isZero();

        BalanceService.UndoResult undo = balances.undoBatch(GUILD, cashout.batchId(), OFFICER);

        // A cashout that never actually happened in game has to be recoverable, and it
        // reverses in the opposite direction to a split.
        assertThat(undo.wasCashout()).isTrue();
        assertThat(undo.totalMoved()).isEqualTo(3_000_000L);
        assertThat(undo.wentNegative()).isEmpty();
        assertThat(balances.totalSilver(GUILD)).isEqualTo(3_000_000L);
        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("a cashout can only be reversed once")
    void undoCashoutIsNotRepeatable() {
        balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());
        BalanceService.CashoutResult cashout = balances.cashOut(GUILD, members, OFFICER, "@payout15", freshClaim());
        balances.undoBatch(GUILD, cashout.batchId(), OFFICER);

        assertThatThrownBy(() -> balances.undoBatch(GUILD, cashout.batchId(), OFFICER))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("already been reversed");

        assertThat(balances.balanceOf(GUILD, 10L)).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("the reversal is written to the ledger against the original batch")
    void undoIsAudited() {
        BalanceService.SplitResult split = balances.creditSplit(GUILD, members, 1_000_000L, OFFICER, "cta", freshClaim());
        balances.undoBatch(GUILD, split.batchId(), OFFICER);

        var entries = ledger.findByReference(split.batchId());

        assertThat(entries).hasSize(6);
        assertThat(entries).filteredOn(t -> t.getType() == TransactionType.SPLIT).hasSize(3);
        assertThat(entries).filteredOn(t -> t.getType() == TransactionType.REVERSAL).hasSize(3);
        // paid and reversed amounts must cancel out exactly
        assertThat(entries.stream().mapToLong(t -> t.getDelta()).sum()).isZero();
    }
}
