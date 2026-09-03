package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A whole recipe, as created.
 * <p>
 * There is no {@code authorId}: the author is the authenticated caller, stamped
 * by JPA auditing. Accepting one from the body would let anyone post a recipe
 * under someone else's name.
 * <p>
 * On the create side there is no {@code Optional} and therefore no ambiguity —
 * {@code categoryIds} is a plain {@code @NotEmpty List<String>}. The
 * absent-versus-empty distinction only arises on update; see
 * {@link UpdateRecipeRequest}.
 *
 * @param categoryIds at least one, so every recipe is classified and therefore
 *                    browsable. A recipe that is honestly both a dessert and a
 *                    goûter carries both
 * @param tagIds      optional, and an empty list is fine
 */
public record CreateRecipeRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        String recommendations,

        @NotEmpty
        List<String> categoryIds,

        List<String> tagIds,

        List<@Valid RecipePictureRequest> coverPictures,

        List<@Valid RecipeIngredientGroupRequest> ingredientGroups,

        List<@Valid RecipeStepRequest> steps
) {
}
