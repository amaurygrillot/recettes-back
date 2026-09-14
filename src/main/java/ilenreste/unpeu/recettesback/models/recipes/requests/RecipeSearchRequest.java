package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;

/**
 * The criteria {@code GET /recipes} accepts, bound from the query string.
 * <p>
 * A record rather than eight {@code @RequestParam} arguments on the controller
 * method. Spring binds a non-annotated object parameter from the query string by
 * name, so the wire format is unchanged — {@code ?title=tarte&tagName=rapide}
 * still works — but the criteria now have one place to be named, documented and
 * validated, and adding a ninth does not mean editing a signature that has
 * already outgrown its line.
 *
 * <h2>Everything is optional, including all of it</h2>
 * No field is required and no combination is. In particular an <strong>entirely
 * empty</strong> request is valid and returns the newest recipes, paginated:
 * that is the home page, and requiring at least one criterion would leave the
 * application with no way to browse. Supplied criteria are combined with AND.
 * <p>
 * Blank strings are treated as absent rather than as "match the empty string",
 * because a form that submits its untouched fields sends {@code ?title=}, and
 * that must not silently become a filter.
 *
 * <h2>A category filter reaches its subcategories unless told not to</h2>
 * Categories form a tree, and {@code ?categoryId=<desserts>} means "show me the
 * desserts" to everyone who is not reading the schema. Matching only recipes
 * carrying that exact row — which is what this did before {@code includeSubcategories}
 * existed — makes a recipe filed under "Tartes" invisible from "Desserts", so the
 * hierarchy renders in the navigation and then does nothing when clicked.
 * <p>
 * So the default is to widen: <strong>an absent {@code includeSubcategories} means
 * true.</strong> It is a {@link Boolean} rather than a {@code boolean} for exactly
 * that reason — a primitive would bind absent to {@code false} and quietly give
 * the wrong default to every client that does not know the parameter exists.
 * <p>
 * {@code includeSubcategories=false} asks for exact membership instead: the
 * recipes filed under <em>this</em> category and no other. That is the right
 * question for an admin checking what is actually in a category before deleting
 * or merging it, and the wrong one for browsing.
 * <p>
 * The flag is free on a flat taxonomy: a category with no children widens to
 * itself, so nothing changes for an installation that never nests anything.
 *
 * <h2>Ids and names, both</h2>
 * The id filters are for a UI that just showed the user a list to pick from; the
 * name filters are for a user typing. Only the first kind existed before, which
 * assumed the caller already knew a UUID for the author, category or tag they
 * wanted — true of a dropdown, false of a search box and false of a hand-written
 * link. Both are kept: matching by name is the friendly path, matching by id is
 * the exact one, and a UI that has the id should not be made to round-trip
 * through a name that might not be unique.
 *
 * @param title        matched against the recipe title, case-insensitively,
 *                     anywhere in the string
 * @param authorId     exactly this author
 * @param authorName   matched against the author's username, case-insensitively,
 *                     anywhere in the string
 * @param categoryId           single-value on purpose. Multi-category filtering —
 *                             "desserts AND rapide" — needs an AND-versus-OR
 *                             decision and a different query shape, and is out of
 *                             scope until a UI asks for it
 * @param categoryName         matched against the category's normalized name, so
 *                             typing {@code entrees} finds {@code Entrées}. A
 *                             recipe matches if <em>any</em> of its categories
 *                             does. This and {@code categoryId} are two spellings
 *                             of one filter: supplying both unions their matches
 *                             rather than intersecting them, see
 *                             {@code CategoryService.findMatchingIds}
 * @param includeSubcategories whether a category filter reaches everything filed
 *                             <em>beneath</em> the category as well, at any depth.
 *                             <strong>Defaults to true</strong> when the parameter
 *                             is absent — see below
 * @param tagId                exactly this tag. Tags are flat, so nothing here
 *                             widens them
 * @param tagName              matched against the tag's normalized name, same
 *                             rules as {@code categoryName}
 * @param sortBy               which column to order by, from a closed set — see
 *                             {@link RecipeSortField} for why it is not free text
 * @param direction            {@code ASC} or {@code DESC}. Independent of
 *                             {@code sortBy}, so "oldest first" and "reverse
 *                             alphabetical" are both already expressible
 */
public record RecipeSearchRequest(

        @Size(max = 200)
        String title,

        String authorId,

        @Size(max = 100)
        String authorName,

        String categoryId,

        @Size(max = 100)
        String categoryName,

        Boolean includeSubcategories,

        String tagId,

        @Size(max = 100)
        String tagName,

        RecipeSortField sortBy,

        Sort.Direction direction
) {

    /**
     * Whether the category filter reaches subcategories, resolving the absent case.
     * <p>
     * Absent means true: see the class javadoc for why that is the default and why
     * the field is a {@link Boolean}.
     */
    public boolean includesSubcategories() {
        return includeSubcategories == null || includeSubcategories;
    }

    /** Newest first: what a recipe library opens on. */
    private static final RecipeSortField DEFAULT_SORT_FIELD = RecipeSortField.CREATED_AT;
    private static final Sort.Direction DEFAULT_DIRECTION = Sort.Direction.DESC;

    /**
     * The ordering to apply, filling in the default for whichever half was not
     * given.
     * <p>
     * Defaulting here rather than with {@code @PageableDefault} on the controller,
     * because the sort is no longer carried by {@code Pageable} at all — see
     * {@link RecipeSortField}.
     */
    public Sort sort() {
        return Sort.by(direction == null ? DEFAULT_DIRECTION : direction,
                (sortBy == null ? DEFAULT_SORT_FIELD : sortBy).property());
    }

}
