package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecipesRepository extends JpaRepository<RecipeEntity, String> {

    /**
     * Page over recipe <strong>ids</strong>, filtering with {@code EXISTS}
     * subqueries rather than joins.
     * <p>
     * The obvious version — {@code Page<RecipeEntity> findByCategories_Id(...)} —
     * is broken in two ways at once: a join onto a to-many collection produces
     * <strong>duplicate rows</strong> (one per matching category), and Spring
     * Data's derived {@code COUNT} then counts those duplicates, so
     * {@code getTotalElements()} lies and the last page is wrong. {@code EXISTS}
     * keeps one row per recipe, so both the page and its count are correct.
     * <p>
     * The {@code :param IS NULL OR ...} form is used instead of a JPA
     * {@code Specification} because four optional filters is exactly the size
     * where the Criteria API costs more in ceremony than it returns. If filters
     * keep being added, revisit.
     * <p>
     * {@code LIKE '%q%'} cannot use a B-tree index, so title search is a
     * sequential scan. Fine for hundreds of recipes; if it ever is not, the fix
     * is a {@code pg_trgm} GIN index or PostgreSQL full-text search, not a
     * different query shape.
     * <p>
     * <strong>{@code :q} is cast explicitly.</strong> A bare null parameter has
     * no type for PostgreSQL to infer inside {@code CONCAT}, so it plans the
     * argument as {@code bytea} and the whole query fails with
     * {@code function lower(bytea) does not exist} — on every unfiltered listing,
     * which is the most common request there is. The other three parameters are
     * compared with {@code =} against a text column and get their type from that.
     */
    @Query("""
            SELECT r.id FROM RecipeEntity r
            WHERE (:authorId   IS NULL OR r.author.id = :authorId)
              AND (:categoryId IS NULL OR EXISTS (SELECT 1 FROM r.categories c WHERE c.id = :categoryId))
              AND (:tagId      IS NULL OR EXISTS (SELECT 1 FROM r.tags       t WHERE t.id = :tagId))
              AND (CAST(:q AS string) IS NULL
                   OR LOWER(r.title) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            """)
    Page<String> searchIds(@Param("authorId") String authorId,
                           @Param("categoryId") String categoryId,
                           @Param("tagId") String tagId,
                           @Param("q") String q,
                           Pageable pageable);

    /**
     * Loads exactly the recipes on one page of {@link #searchIds}.
     * <p>
     * <strong>{@code IN} does not preserve order.</strong> These rows come back in
     * whatever order PostgreSQL likes, so the caller must reorder them to match
     * the page's id list before mapping — otherwise the sort the user asked for
     * silently disappears. This is the single easiest thing to get wrong in the
     * whole design.
     * <p>
     * The author is fetch-joined because every summary renders their name;
     * {@code categories}, {@code tags} and {@code coverPictures} are filled by
     * Hibernate's batch fetching in a bounded number of extra queries rather than
     * one per row.
     */
    @Query("SELECT r FROM RecipeEntity r JOIN FETCH r.author WHERE r.id IN :ids")
    List<RecipeEntity> findAllForSummary(@Param("ids") Collection<String> ids);

    /**
     * Permission and existence in one scalar query.
     * <p>
     * {@code PUT} needs the whole entity anyway, but {@code DELETE} does not, and
     * this checks both without materialising a recipe that is about to be thrown
     * away. An empty {@link Optional} is the 404; a mismatch is the 403.
     */
    @Query("SELECT r.author.id FROM RecipeEntity r WHERE r.id = :id")
    Optional<String> findAuthorIdById(@Param("id") String id);

    // The four reference-usage checks live here rather than on the reference repositories, because
    // they all ask the same question - "does any recipe still point at this?" - and keeping them
    // together is what stops a fifth variant being invented later. Duplicate rows are harmless:
    // the question is only whether the count exceeds zero.

    @Query("SELECT COUNT(r) > 0 FROM RecipeEntity r JOIN r.categories c WHERE c.id = :categoryId")
    boolean isCategoryUsed(@Param("categoryId") String categoryId);

    @Query("SELECT COUNT(r) > 0 FROM RecipeEntity r JOIN r.tags t WHERE t.id = :tagId")
    boolean isTagUsed(@Param("tagId") String tagId);

    @Query("""
            SELECT COUNT(ri) > 0 FROM RecipeEntity r
            JOIN r.ingredientGroups g JOIN g.ingredients ri
            WHERE ri.ingredient.id = :ingredientId
            """)
    boolean isIngredientUsed(@Param("ingredientId") String ingredientId);

    @Query("""
            SELECT COUNT(ri) > 0 FROM RecipeEntity r
            JOIN r.ingredientGroups g JOIN g.ingredients ri
            WHERE ri.unit.id = :unitId
            """)
    boolean isUnitUsed(@Param("unitId") String unitId);
}
