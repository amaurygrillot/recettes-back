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
 * @param introduction     optional block rendered above everything else. Omit it
 *                         — or send it with blank text, which means the same
 *                         thing here — for a recipe that opens straight into its
 *                         ingredients
 * @param categoryIds      at least one, so every recipe is classified and
 *                         therefore browsable. A recipe that is honestly both a
 *                         dessert and a goûter carries both
 * @param tagIds           optional, and an empty list is fine
 * @param coverPictures    optional. A recipe with no photograph is still a recipe
 * @param ingredientGroups <strong>at least one, holding at least one line.</strong>
 *                         A recipe with no ingredients is not a recipe that has
 *                         not been finished — it is a recipe nobody can cook, and
 *                         accepting one only moves the failure to the reader. The
 *                         grouping is a presentation detail, so a recipe with a
 *                         single untitled group is the ordinary case, not a
 *                         special one
 * @param steps            at least one, for the same reason
 */
public record CreateRecipeRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        RecipeIntroductionRequest introduction,

        String recommendations,

        @NotEmpty
        List<String> categoryIds,

        List<String> tagIds,

        List<@Valid RecipePictureRequest> coverPictures,

        @NotEmpty
        List<@Valid RecipeIngredientGroupRequest> ingredientGroups,

        @NotEmpty
        List<@Valid RecipeStepRequest> steps
) {
}
