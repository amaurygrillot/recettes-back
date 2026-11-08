package ilenreste.unpeu.recettesback.mappers.recipes;

import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A list row plus the aggregates computed for it. The count and the covers are not on the entity — they come
 * from queries made for many lists at once, which is what keeps {@code GET /lists} out of N+1 — so they are
 * passed in rather than read off a collection that does not exist.
 */
@Component
public class RecipeListMapper {

    /**
     * @param containsRecipe whether the recipe asked about is in this list, or {@code null} when no recipe was
     *                       asked about, which leaves the property out of the JSON altogether
     */
    public RecipeListResponse toResponse(RecipeListEntity list, long recipeCount, List<String> coverMediaIds,
                                         Boolean containsRecipe) {
        return new RecipeListResponse(
                list.getId(),
                list.getName(),
                list.getEmoji(),
                recipeCount,
                list.getCreatedAt(),
                list.getUpdatedAt(),
                coverMediaIds,
                containsRecipe
        );
    }
}
