package personal.albiondiscordbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import personal.albiondiscordbot.domain.KillboardPost;

public interface KillboardPostRepository extends JpaRepository<KillboardPost, KillboardPost.Key> {

    boolean existsByKey(KillboardPost.Key key);
}
