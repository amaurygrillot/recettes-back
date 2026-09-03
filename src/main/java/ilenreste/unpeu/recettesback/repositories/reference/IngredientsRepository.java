package ilenreste.unpeu.recettesback.repositories.reference;

import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface IngredientsRepository extends ReferenceRepository<IngredientEntity> {

    /**
     * Autocomplete for the recipe editor.
     * <p>
     * <strong>{@code StartingWith}, not {@code Containing}</strong>: a trailing
     * wildcard uses the index on {@code normalized_name}, a leading one cannot.
     * Searching the normalized column is also what makes typing {@code oeuf} find
     * {@code Oeuf}.
     * <p>
     * Only this repository needs a paged search — its table is the one that grows.
     * Categories, tags and units are small enough that their endpoints return
     * everything.
     */
    Page<IngredientEntity> findByNormalizedNameStartingWithOrderByName(String prefix, Pageable pageable);
}
