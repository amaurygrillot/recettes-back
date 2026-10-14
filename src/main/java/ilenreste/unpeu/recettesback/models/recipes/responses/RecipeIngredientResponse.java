package ilenreste.unpeu.recettesback.models.recipes.responses;

import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;

import java.math.BigDecimal;

/**
 * One ingredient line, resolved.
 * <p>
 * The ingredient and unit are embedded rather than referenced by id, so
 * rendering a recipe takes one request instead of one per distinct ingredient.
 *
 * @param quantity null where no amount is meaningful ("sel, poivre")
 * @param unit     null for a bare count ("3 oeufs")
 */
public record RecipeIngredientResponse(
        IngredientResponse ingredient,
        BigDecimal quantity,
        UnitResponse unit,
        String note
) {
}
