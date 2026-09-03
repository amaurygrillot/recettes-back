package ilenreste.unpeu.recettesback.models.recipes.responses;

import java.util.List;

/**
 * A block of ingredient lines.
 *
 * @param title null means "render the list with no heading", which is the common
 *              case - most recipes have a single unnamed list
 */
public record RecipeIngredientGroupResponse(String title, List<RecipeIngredientResponse> ingredients) {
}
