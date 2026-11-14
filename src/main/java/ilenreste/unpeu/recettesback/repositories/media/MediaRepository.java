package ilenreste.unpeu.recettesback.repositories.media;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * <h2>Public lookups and the photo of a list</h2>
 * A {@code media} row that a recipe list points at ({@code recipe_lists.cover_media_id}) is that list's own photo:
 * <strong>private</strong>, read only through its list and never accepted as a recipe's picture or an ingredient's
 * icon. Nothing marks it on the row itself, so every lookup that turns an id taken from a <em>request</em> into a
 * row has to exclude it: that is {@link #findPublicById} and {@link #findAllPublicById}, and they are what
 * {@code GET /media/{id}}, the recipe write path and the ingredient write path use. <strong>Public code must not
 * call the inherited {@code findById} or {@code findAllById}</strong>, which see every row — they are for code that
 * already knows whose row it is, such as the list's own cover service and the tests.
 * <p>
 * The three probes are indexed: {@code recipe_lists.cover_media_id} is unique, and a unique constraint is an index.
 */
public interface MediaRepository extends JpaRepository<MediaEntity, String> {

    /**
     * The row for an id taken from a request, unless it is a list's own photo. Empty for an unknown id and for a
     * list's photo alike, so the answer cannot be told apart.
     */
    @Query("""
            SELECT m FROM MediaEntity m
            WHERE m.id = :id
              AND NOT EXISTS (SELECT 1 FROM RecipeListEntity l WHERE l.cover = m)
            """)
    Optional<MediaEntity> findPublicById(@Param("id") String id);

    /** {@link #findPublicById} for many ids, in one statement. */
    @Query("""
            SELECT m FROM MediaEntity m
            WHERE m.id IN :ids
              AND NOT EXISTS (SELECT 1 FROM RecipeListEntity l WHERE l.cover = m)
            """)
    List<MediaEntity> findAllPublicById(@Param("ids") Collection<String> ids);

    /**
     * Total bytes a user has stored, for the per-user quota.
     * <p>
     * Written now because it is cheap while the entity is being defined and
     * awkward to retrofit; enforcement is deliberately deferred (see
     * {@code docs/media-storage.md}).
     * <p>
     * A list's own photo <strong>counts</strong>: it uses disk and carries an uploader like any other row. A
     * replaced or removed photo is deleted, so it stops counting.
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
     * <strong>A list's own photo is not an orphan</strong>: the fourth probe is its list. A photo no list points at
     * is, as any unreferenced row.
     * <p>
     * Every one of the four {@code NOT EXISTS} probes is indexed from the
     * referencing side. PostgreSQL does not index foreign-key columns
     * automatically — only primary keys and unique constraints get one for free — so
     * without those indexes each candidate row costs four sequential scans.
     */
    @Query("""
            SELECT m FROM MediaEntity m
            WHERE m.createdAt < :threshold
              AND NOT EXISTS (SELECT 1 FROM RecipeCoverPictureEntity c WHERE c.media = m)
              AND NOT EXISTS (SELECT 1 FROM RecipeStepPictureEntity  s WHERE s.media = m)
              AND NOT EXISTS (SELECT 1 FROM IngredientEntity         i WHERE i.icon  = m)
              AND NOT EXISTS (SELECT 1 FROM RecipeListEntity         l WHERE l.cover = m)
            """)
    List<MediaEntity> findOrphans(@Param("threshold") Instant threshold);
}
