package ilenreste.unpeu.recettesback.models.recipes.responses;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One recipe list, as an item of {@code GET /lists} and as the body of every other route that answers with a list.
 *
 * @param id                  the list id
 * @param name                as the owner typed it, trimmed
 * @param emoji               the list's emoji
 * @param recipeCount         every recipe of the list, with or without a picture
 * @param createdAt           when the list was created
 * @param updatedAt           changes when the list is renamed, when its emoji changes, or when its own photo is set,
 *                            replaced or removed, and <strong>only then</strong>: adding or removing a recipe does not
 *                            touch it, and neither does removing a photo the list does not have
 * @param coverVersion        {@code null} (present in the JSON, as {@code null}) when the list has no photo of its
 *                            own; otherwise an opaque string that changes whenever the photo does, which is the
 *                            {@code v} of {@code GET /lists/{id}/cover}. Each version has its own URL, so that a
 *                            replaced photo never comes back from a cache
 * @param recipeCoverMediaIds up to 4 media ids for the photo mosaic of the tile: the first cover picture of the most
 *                            recently added recipes of the list that have one, newest first. Empty, never absent, when
 *                            no recipe of the list has a picture. Fetch each as {@code /media/{id}?variant=thumbnail}.
 *                            Computed whether or not the list has a photo of its own, and the client decides which to
 *                            show. Called {@code coverMediaIds} before a list could have a photo of its own; it is not
 *                            the field of the same name on {@link RecipeSummaryResponse}, which keeps it
 * @param containsRecipe      whether the recipe named by {@code GET /lists?containing=} is in this list.
 *                            <strong>Absent from the JSON, not {@code false}</strong>, whenever {@code containing} was
 *                            not given — which is every route but that one
 */
public record RecipeListResponse(
        String id,
        String name,
        String emoji,
        long recipeCount,
        Instant createdAt,
        Instant updatedAt,
        String coverVersion,
        List<String> recipeCoverMediaIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean containsRecipe
) {
}
