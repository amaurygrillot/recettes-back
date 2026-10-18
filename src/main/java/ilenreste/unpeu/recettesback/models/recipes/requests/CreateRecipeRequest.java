package ilenreste.unpeu.recettesback.models.recipes.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
 *                         dessert and a goûter carries both.
 *                         <p>
 *                         {@code @NotBlank} on the <em>element</em> and not only
 *                         {@code @NotEmpty} on the list: {@code ["" ]} is not an
 *                         empty list, so it used to pass, and
 *                         {@code ReferenceResolver} ignores blanks — the recipe
 *                         was then stored with no categories at all and answered
 *                         201, which is exactly the invariant this field exists
 *                         to hold
 * @param tagIds           optional, and an empty list is fine
 * @param coverPictures    optional. {@code @NotNull} on the element because
 *                         Hibernate Validator treats cascading into a {@code null}
 *                         container element as a no-op, so {@code [null]} reached
 *                         {@code RecipeService} and dereferenced there as a 500
 *                         instead of a 400
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

        @JsonProperty(required = false)
        RecipeIntroductionRequest introduction,

        @JsonProperty(required = false)
        String recommendations,

        @NotEmpty
        List<@NotBlank String> categoryIds,

        @JsonProperty(required = false)
        List<@NotBlank String> tagIds,

        @JsonProperty(required = false)
        List<@NotNull @Valid RecipePictureRequest> coverPictures,

        @NotEmpty
        List<@NotNull @Valid RecipeIngredientGroupRequest> ingredientGroups,

        @NotEmpty
        List<@NotNull @Valid RecipeStepRequest> steps
) {
}
