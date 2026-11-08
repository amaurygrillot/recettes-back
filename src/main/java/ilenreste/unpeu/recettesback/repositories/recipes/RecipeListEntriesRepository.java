package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntryEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Everything that reads or writes the entries of recipe lists. Every method here is one statement whatever
 * the number of lists it is asked about, which is what keeps {@code GET /lists} out of N+1.
 */
public interface RecipeListEntriesRepository extends JpaRepository<RecipeListEntryEntity, RecipeListEntryId> {

    /** For the limit on recipes per list. */
    @Query("SELECT COUNT(e) FROM RecipeListEntryEntity e WHERE e.list.id = :listId")
    long countByListId(@Param("listId") String listId);

    /**
     * How many recipes each of these lists holds, in one statement. A list with none has no row here, so the
     * caller defaults to 0.
     */
    @Query("""
            SELECT e.list.id AS listId, COUNT(e) AS recipeCount
            FROM RecipeListEntryEntity e
            WHERE e.list.id IN :listIds
            GROUP BY e.list.id
            """)
    List<EntryCount> countEntriesOf(@Param("listIds") Collection<String> listIds);

    /**
     * The cover mosaic of every one of these lists, in one statement: for each list, the first cover picture of
     * its {@code limit} most recently added recipes that have one, newest first.
     * <p>
     * Native SQL because JPQL has no "top N per group". The alternatives were a correlated rank count
     * (quadratic per list) or reading every entry of every list into Java to keep four of each. The window
     * function is standard SQL, and this project is PostgreSQL-only.
     * <p>
     * A recipe's <em>first</em> cover is the one with the lowest {@code position}, which is unique per recipe.
     * A recipe without a cover has no row in the inner join, so it is skipped rather than taking a slot: the
     * mosaic of a list whose newest recipes have no photo shows the older ones'. The alias is quoted because
     * PostgreSQL folds unquoted ones to lower case and the projection reads them by name.
     */
    @Query(nativeQuery = true, value = """
            SELECT ranked.list_id AS "listId", ranked.media_id AS "mediaId"
            FROM (
                SELECT e.list_id, c.media_id,
                       ROW_NUMBER() OVER (PARTITION BY e.list_id ORDER BY e.added_at DESC, e.recipe_id) AS slot
                FROM recipe_list_entries e
                JOIN recipe_cover_pictures c
                  ON c.recipe_id = e.recipe_id
                 AND c.position = (SELECT MIN(first_cover.position) FROM recipe_cover_pictures first_cover
                                   WHERE first_cover.recipe_id = e.recipe_id)
                WHERE e.list_id IN (:listIds)
            ) ranked
            WHERE ranked.slot <= :limit
            ORDER BY ranked.list_id, ranked.slot
            """)
    List<ListCover> findCovers(@Param("listIds") Collection<String> listIds, @Param("limit") int limit);

    /** Which of this owner's lists hold the recipe, in one statement for all of them. */
    @Query("""
            SELECT e.list.id FROM RecipeListEntryEntity e
            WHERE e.recipe.id = :recipeId AND e.list.owner.id = :ownerId
            """)
    Set<String> findListIdsHolding(@Param("recipeId") String recipeId, @Param("ownerId") String ownerId);

    /**
     * Puts a recipe in a list, or does nothing if it is already there, atomically.
     * <p>
     * {@code ON CONFLICT DO NOTHING} rather than {@code existsById} then {@code save}: two identical requests in
     * flight at once (a double click, a retry) would both pass the check, and the second would die on the
     * primary key — a 409 for an operation defined as safe to repeat. Catching that in the same transaction
     * does not help, PostgreSQL having aborted it. This is the one statement that cannot lose the race.
     *
     * @return 1 if the recipe was added, 0 if it was already there
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO recipe_list_entries (list_id, recipe_id, added_at)
            VALUES (:listId, :recipeId, :addedAt)
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(@Param("listId") String listId, @Param("recipeId") String recipeId,
                       @Param("addedAt") Instant addedAt);

    /** @return 1 if the recipe was in the list and is not any more, 0 if it was not there */
    @Modifying
    @Query("DELETE FROM RecipeListEntryEntity e WHERE e.list.id = :listId AND e.recipe.id = :recipeId")
    int deleteEntry(@Param("listId") String listId, @Param("recipeId") String recipeId);

    /** {@code COUNT} per list. */
    interface EntryCount {
        String getListId();

        long getRecipeCount();
    }

    /** One picture of the mosaic of one list. */
    interface ListCover {
        String getListId();

        String getMediaId();
    }
}
