package ilenreste.unpeu.recettesback.repositories.media;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

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

    /**
     * Media uploaded and then never attached — a user who abandoned the form.
     * <p>
     * Also written now and <strong>not scheduled</strong>: wiring a job that
     * deletes rows and files is a separate decision from being able to find them.
     * See {@code docs/media-storage.md}.
     * <p>
     * The {@code created_at} threshold is what keeps this safe to run at all: an
     * upload that is seconds old and not yet referenced is almost certainly a
     * form still being filled in, not an orphan.
     * <p>
     * Every one of the three {@code NOT EXISTS} probes is indexed from the
     * referencing side. PostgreSQL does not index foreign-key columns
     * automatically — only primary keys and unique constraints get one free — so
     * without those indexes each candidate row costs three sequential scans.
     */
    @Query("""
            SELECT m FROM MediaEntity m
            WHERE m.createdAt < :threshold
              AND NOT EXISTS (SELECT 1 FROM RecipeCoverPictureEntity c WHERE c.media = m)
              AND NOT EXISTS (SELECT 1 FROM RecipeStepPictureEntity  s WHERE s.media = m)
              AND NOT EXISTS (SELECT 1 FROM IngredientEntity         i WHERE i.icon  = m)
            """)
    List<MediaEntity> findOrphans(@Param("threshold") Instant threshold);
}
