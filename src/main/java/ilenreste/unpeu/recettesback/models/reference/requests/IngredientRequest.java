package ilenreste.unpeu.recettesback.models.reference.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or update an ingredient.
 *
 * @param name         the ingredient as the user typed it
 * @param iconMediaId  an optional already-uploaded media id. Media is uploaded
 *                     first and independently, then referenced by id - the same
 *                     "reference data must exist first" rule that applies to the
 *                     ingredient itself. Null clears the icon on update
 */
public record IngredientRequest(

        @NotBlank
        @Size(min = 1, max = 100)
        String name,

        String iconMediaId
) {
}
