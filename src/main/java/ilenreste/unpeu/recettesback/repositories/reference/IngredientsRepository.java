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
     * <p>
     * No {@code OrderByName} in the name any more: the ordering now arrives on the
     * {@link Pageable}, built from the closed {@code IngredientSortField} enum, and
     * Spring Data appends a {@code Pageable}'s sort to whatever the method name
     * already asked for. Keeping both meant two orderings in one query, with the
     * static one silently winning the first position.
     */
    Page<IngredientEntity> findByNormalizedNameStartingWith(String prefix, Pageable pageable);
}
