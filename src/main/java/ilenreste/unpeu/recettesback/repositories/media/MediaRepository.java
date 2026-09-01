package ilenreste.unpeu.recettesback.repositories.media;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaRepository extends JpaRepository<MediaEntity, String> {

    /**
     * Total bytes a user has stored, for the per-user quota.
     * <p>
     * Written now because it is cheap while the entity is being defined and
     * awkward to retrofit; enforcement is deliberately deferred (see
     * {@code docs/media-storage.md}).
     * <p>
     * {@code COALESCE} is load-bearing: {@code SUM} over zero rows returns
     * {@code null}, and a {@code long} return type then throws on unboxing the
     * very first time a user with no uploads is checked - which is every user,
     * once.
     */
    @Query("SELECT COALESCE(SUM(m.sizeBytes), 0) FROM MediaEntity m WHERE m.uploadedBy.id = :userId")
    long totalBytesUploadedBy(@Param("userId") String userId);

    // findOrphans lands with the recipes entities: it probes recipe_cover_pictures,
    // recipe_step_pictures and ingredients through NOT EXISTS, none of which exist yet.
}
