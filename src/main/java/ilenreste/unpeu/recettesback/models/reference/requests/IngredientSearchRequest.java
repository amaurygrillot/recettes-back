package ilenreste.unpeu.recettesback.models.reference.requests;

import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;

/**
 * The criteria {@code GET /ingredients} accepts, bound from the query string.
 * <p>
 * A record rather than loose {@code @RequestParam}s, matching
 * {@code RecipeSearchRequest}: Spring binds a non-annotated object parameter by
 * name, so the wire format is unchanged — {@code ?namePrefix=oeuf} still works —
 * but the criteria gain one place to be named, documented and validated, and the
 * ordering gains somewhere to live that is not a free-text {@code sort}.
 *
 * @param namePrefix what the user has typed so far, matched against the
 *                   normalized name — which is what makes typing {@code oeuf}
 *                   find {@code Œuf}. A prefix and not a substring, because a
 *                   trailing wildcard can use the index and a leading one
 *                   cannot. Absent or blank returns everything
 * @param sortBy     which column to order by, from a closed set — see
 *                   {@link IngredientSortField} for why it is not free text
 * @param direction  {@code ASC} or {@code DESC}, independent of {@code sortBy}
 */
public record IngredientSearchRequest(

        @Size(max = 100)
        String namePrefix,

        IngredientSortField sortBy,

        Sort.Direction direction
) {

    private static final IngredientSortField DEFAULT_SORT_FIELD = IngredientSortField.NAME;
    private static final Sort.Direction DEFAULT_DIRECTION = Sort.Direction.ASC;

    /** A blank query parameter means "no filter", not "match the empty string". */
    public String namePrefix() {
        return namePrefix == null || namePrefix.isBlank() ? null : namePrefix;
    }

    /**
     * The ordering to apply, filling in the default for whichever half was not
     * given. Alphabetical ascending unless asked otherwise, because this list
     * feeds an autocomplete.
     */
    public Sort sort() {
        return Sort.by(direction == null ? DEFAULT_DIRECTION : direction,
                (sortBy == null ? DEFAULT_SORT_FIELD : sortBy).property());
    }
}
