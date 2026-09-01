package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * One instruction, in the order it appears in the enclosing list.
 * <p>
 * {@code instruction} is {@code @NotBlank} and that annotation is
 * load-bearing, not decoration: {@code recipe_steps.instruction} is
 * {@code NOT NULL}, so without it a step with a null instruction reaches the
 * database and comes back as a {@code DataIntegrityViolationException}. That
 * would be reported as a generic 500 rather than telling the caller which field
 * is wrong — see {@code docs/api-error-handling.md}.
 */
public record RecipeStepRequest(

        @NotBlank
        String instruction,

        List<@Valid RecipePictureRequest> pictures
) {
}
