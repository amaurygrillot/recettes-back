package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecipeListsRepository extends JpaRepository<RecipeListEntity, String> {

    /**
     * A member's lists in the order the tiles are shown: by normalised name, so accents and case do not decide,
     * then as typed. The second key cannot break a tie for one member today, because the unique constraint
     * forbids two lists with the same normalised name; it keeps the order total should that ever be relaxed.
     */
    List<RecipeListEntity> findByOwnerIdOrderByNormalizedNameAscNameAsc(String ownerId);

    /** For the limit on lists per member. */
    long countByOwnerId(String ownerId);

    /**
     * The pre-check behind the clean 409 on a duplicate name. The unique constraint on
     * {@code (owner_id, normalized_name)} is what actually guarantees it: this loses a race.
     */
    Optional<RecipeListEntity> findByOwnerIdAndNormalizedName(String ownerId, String normalizedName);
}
