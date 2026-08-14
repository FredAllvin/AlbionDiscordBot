package personal.albiondiscordbot.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import personal.albiondiscordbot.domain.BalanceTransaction;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, Long> {

    List<BalanceTransaction> findByDiscordGuildIdAndDiscordUserIdOrderByCreatedAtDesc(
            Long discordGuildId, Long discordUserId, Pageable pageable);

    List<BalanceTransaction> findByReference(String reference);
}
