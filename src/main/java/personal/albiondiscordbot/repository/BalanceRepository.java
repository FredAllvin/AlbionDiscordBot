package personal.albiondiscordbot.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import personal.albiondiscordbot.domain.Balance;

/**
 * Read-side access to balances. All writes go through {@code BalanceDao}, which uses
 * single atomic SQL statements — see the notes on {@link Balance}.
 */
public interface BalanceRepository extends JpaRepository<Balance, Long> {

    Optional<Balance> findByDiscordGuildIdAndDiscordUserId(Long discordGuildId, Long discordUserId);

    List<Balance> findByDiscordGuildIdOrderByAmountDesc(Long discordGuildId);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Balance b WHERE b.discordGuildId = :discordGuildId")
    long sumAmountByDiscordGuildId(Long discordGuildId);
}
