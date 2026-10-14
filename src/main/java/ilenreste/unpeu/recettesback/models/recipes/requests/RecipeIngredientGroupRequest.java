package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A block of ingredient lines, optionally titled.
 *
 * @param title       null on purpose for the common case. Most recipes have a
 *                    single unnamed ingredient list, and forcing a title there
 *                    would make every such recipe carry a meaningless
 *                    "Ingrédients" heading. Recipes that do split ("Pour la
 *                    pâte" / "Pour la garniture") title every group
 * @param ingredients at least one — an empty group is a heading with nothing
 *                    under it, which is never what someone meant to send
 */
public record RecipeIngredientGroupRequest(

        @Size(max = 200)
        String title,

        @NotEmpty
        List<@Valid RecipeIngredientRequest> ingredients
) {
}
