package personal.albiondiscordbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import personal.albiondiscordbot.domain.PollerState;

public interface PollerStateRepository extends JpaRepository<PollerState, String> {
}
