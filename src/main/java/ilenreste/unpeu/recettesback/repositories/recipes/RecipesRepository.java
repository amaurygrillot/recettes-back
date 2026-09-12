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
     * keeps one row per recipe, so both the page and its count are correct. That
     * matters more now than it did with three filters: {@code categoryName} and
     * {@code tagName} are to-many <em>and</em> non-unique, so a recipe carrying
     * two categories whose names both match would otherwise appear twice.
     * <p>
     * The {@code :param IS NULL OR ...} form is used instead of a JPA
     * {@code Specification} because seven optional filters that are all combined
     * the same way (AND, exact or LIKE) is still below the size where the Criteria
     * API returns more than it costs in ceremony. If filters start needing
     * different combinators, revisit.
     * <p>
     * {@code LIKE '%q%'} cannot use a B-tree index, so the title, author-name,
     * category-name and tag-name searches are sequential scans. Fine for hundreds
     * of recipes; if it ever is not, the fix is a {@code pg_trgm} GIN index or
     * PostgreSQL full-text search, not a different query shape.
     * <p>
     * <strong>Every text parameter is cast explicitly.</strong> A bare null
     * parameter has no type for PostgreSQL to infer inside {@code CONCAT} or
     * {@code LOWER}, so it plans the argument as {@code bytea} and the whole query
     * fails with {@code function lower(bytea) does not exist} — on every
     * unfiltered listing, which is the most common request there is. This cost a
     * shipped bug once already; the three id parameters are compared with
     * {@code =} against a text column and get their type from that, which is why
     * only these four carry the cast.
     * <p>
     * The name filters match {@code normalized_name} rather than {@code name},
     * which is what makes typing {@code entrees} find {@code Entrées} — the caller
     * must normalize the term the same way before passing it in. The author is
     * matched on {@code username} instead, since users have no normalized column.
     *
     * @param title          substring of the recipe title, case-insensitive, or null
     * @param authorId       exact author id, or null
     * @param authorName     substring of the author's username, case-insensitive, or null
     * @param categoryId     exact category id, or null
     * @param categoryName   substring of a category's <em>normalized</em> name, or null
     * @param tagId          exact tag id, or null
     * @param tagName        substring of a tag's <em>normalized</em> name, or null
     */
    @Query("""
            SELECT r.id FROM RecipeEntity r
            WHERE (:authorId   IS NULL OR r.author.id = :authorId)
              AND (:categoryId IS NULL OR EXISTS (SELECT 1 FROM r.categories c WHERE c.id = :categoryId))
              AND (:tagId      IS NULL OR EXISTS (SELECT 1 FROM r.tags       t WHERE t.id = :tagId))
              AND (CAST(:title AS string) IS NULL
                   OR LOWER(r.title) LIKE LOWER(CONCAT('%', CAST(:title AS string), '%')))
              AND (CAST(:authorName AS string) IS NULL
                   OR LOWER(r.author.username) LIKE LOWER(CONCAT('%', CAST(:authorName AS string), '%')))
              AND (CAST(:categoryName AS string) IS NULL
                   OR EXISTS (SELECT 1 FROM r.categories c
                              WHERE c.normalizedName LIKE CONCAT('%', CAST(:categoryName AS string), '%')))
              AND (CAST(:tagName AS string) IS NULL
                   OR EXISTS (SELECT 1 FROM r.tags t
                              WHERE t.normalizedName LIKE CONCAT('%', CAST(:tagName AS string), '%')))
            """)
    Page<String> searchIds(@Param("title") String title,
                           @Param("authorId") String authorId,
                           @Param("authorName") String authorName,
                           @Param("categoryId") String categoryId,
                           @Param("categoryName") String categoryName,
                           @Param("tagId") String tagId,
                           @Param("tagName") String tagName,
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
