package ilenreste.unpeu.recettesback.models.recipes.responses;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One recipe list, as an item of {@code GET /lists} and as the body of every other route that answers with a list.
 *
 * @param id             the list id
 * @param name           as the owner typed it, trimmed
 * @param emoji          the list's emoji
 * @param recipeCount    every recipe of the list, with or without a picture
 * @param createdAt      when the list was created
 * @param updatedAt      changes when the list is renamed or its emoji changes, and <strong>only then</strong>:
 *                       adding or removing a recipe does not touch it
 * @param coverMediaIds  up to 4 media ids for the photo mosaic of the tile: the first cover picture of the most
 *                       recently added recipes of the list that have one, newest first. Empty, never absent, when no
 *                       recipe of the list has a picture. Fetch each as {@code /media/{id}?variant=thumbnail}
 * @param containsRecipe whether the recipe named by {@code GET /lists?containing=} is in this list. <strong>Absent
 *                       from the JSON, not {@code false}</strong>, whenever {@code containing} was not given — which
 *                       is every route but that one
 */
public record RecipeListResponse(
        String id,
        String name,
        String emoji,
        long recipeCount,
        Instant createdAt,
        Instant updatedAt,
        List<String> coverMediaIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean containsRecipe
) {
}
