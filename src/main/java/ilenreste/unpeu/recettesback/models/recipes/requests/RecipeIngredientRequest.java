package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * One line of an ingredient list: "200 g de farine, tamisée".
 *
 * @param ingredientId the shared ingredient this line refers to
 * @param quantity     optional. Null covers "sel, poivre" and "de l'huile",
 *                     where no amount is meaningful. {@link BigDecimal} and
 *                     never {@code double}: floating-point rounding artifacts
 *                     have no place in a quantity a human typed
 * @param unitId       optional. Null means a bare count ("3 œufs") — a
 *                     {@code PIECE} unit was rejected because it would force
 *                     every recipe to pick one and put "3 pièces œufs" in front
 *                     of the renderer
 * @param note         the qualifier belonging to <em>this line</em> rather than
 *                     to the ingredient itself ("finement haché"). Without it,
 *                     people encode that into the ingredient name and the shared
 *                     table fills up with rows like {@code oignon finement haché}
 */
public record RecipeIngredientRequest(

        @NotBlank
        String ingredientId,

        // Matches the NUMERIC(10,3) column: three decimals is enough for 0.5 tsp or 0.25 L
        // without inviting nonsense precision, and rejecting it here beats a database error.
        @DecimalMin("0.0")
        @Digits(integer = 7, fraction = 3)
        BigDecimal quantity,

        String unitId,

        @Size(max = 200)
        String note
) {
}
