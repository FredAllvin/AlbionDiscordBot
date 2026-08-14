package personal.albiondiscordbot.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import personal.albiondiscordbot.domain.PlayerFameSnapshot;

public interface PlayerFameSnapshotRepository extends JpaRepository<PlayerFameSnapshot, Long> {

    Optional<PlayerFameSnapshot> findFirstByAlbionPlayerIdOrderByCapturedAtDesc(String albionPlayerId);
}
