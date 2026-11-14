package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface RecipeListsRepository extends JpaRepository<RecipeListEntity, String> {

    /**
     * The list, loaded <strong>with a row lock</strong> ({@code PESSIMISTIC_WRITE}), which every write to the list's own
     * photo and the deletion of the list take. The lock is what serialises overlapping writes to one list's photo,
     * and what makes {@code DELETE /lists/{id}} see the photo a concurrent {@code PUT} has just attached — it reads
     * the current row, after waiting for the lock, not the one it might have loaded a moment before.
     * <p>
     * Empty if the list does not exist: for a caller that waited, that means it was deleted meanwhile.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecipeListEntity> findWithLockById(String id);

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
