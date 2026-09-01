package ilenreste.unpeu.recettesback.models.recipes.requests;

import ilenreste.unpeu.recettesback.validation.NotEmptyIfPresent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;

/**
 * A partial update. An absent field is left alone; a present collection
 * <strong>replaces the whole collection</strong>.
 * <p>
 * Replacement rather than per-element patching because the editing UI is a form
 * holding the entire recipe, so it always knows the full list. It also makes
 * reordering free, where patching needs explicit position juggling.
 *
 * <h2>Absent and empty are different requests</h2>
 * {@code Optional} answers "was this field supplied?"; {@code @NotEmpty} answers
 * "is the supplied value usable?". Those are different questions, and
 * {@code categoryIds} is the field where both need answering:
 * <table>
 *   <caption>The three states the JSON can express</caption>
 *   <tr><th>Body</th><th>Means</th><th>Outcome</th></tr>
 *   <tr><td>field omitted</td><td>don't touch categories</td><td>200, unchanged</td></tr>
 *   <tr><td>{@code "categoryIds": []}</td><td>set categories to none</td><td><strong>400</strong></td></tr>
 *   <tr><td>{@code "categoryIds": ["x"]}</td><td>set categories to x</td><td>200</td></tr>
 * </table>
 * Drop the constraint and the middle row silently succeeds, leaving the recipe
 * uncategorised and breaking the invariant everything else leans on.
 * {@link CreateRecipeRequest} enforces it at creation; an update must not be a
 * back door around it.
 * <p>
 * The contrast with {@code tagIds} directly below is the point: same
 * {@code Optional<List<String>>} shape, no constraint, because clearing every tag
 * is a legitimate edit. Same type, deliberately different rule, and the
 * annotation is the only thing expressing the difference.
 *
 * <h2>Why the rule is {@link NotEmptyIfPresent} and not {@code @NotEmpty}</h2>
 * The obvious spelling, {@code Optional<@NotEmpty List<String>> categoryIds},
 * is <strong>wrong</strong> — and wrong in the quiet direction. Hibernate
 * Validator's {@code OptionalValueExtractor} is {@code @UnwrapByDefault} and
 * extracts {@code Optional.orElse(null)}, so an <em>absent</em> field reaches
 * the constraint as {@code null}, which {@code @NotEmpty} rejects. Every
 * partial update that does not mention categories would answer 400: exactly
 * what the {@code Optional} was chosen to prevent.
 * <p>
 * Putting the annotation outside the diamond is no better, and the existing
 * {@code UpdateUserRequest} is not a precedent either — it works only because
 * {@code @Size} is null-tolerant by specification, so it passes on the absent
 * case by accident rather than by design.
 * <p>
 * {@link NotEmptyIfPresent} says the thing that is actually meant: absent is
 * fine, supplied-but-empty is not. Null-tolerant constraints such as
 * {@code @Size} still work inside the diamond and are used there.
 * {@code UpdateRecipeRequestValidationTest} pins all of this down so nobody has
 * to reason about it again.
 */
public record UpdateRecipeRequest(

        @NotEmptyIfPresent
        Optional<@Size(max = 200) String> title,

        Optional<String> recommendations,

        @NotEmptyIfPresent
        Optional<List<String>> categoryIds,

        Optional<List<String>> tagIds,

        Optional<List<@Valid RecipePictureRequest>> coverPictures,

        Optional<List<@Valid RecipeIngredientGroupRequest>> ingredientGroups,

        Optional<List<@Valid RecipeStepRequest>> steps
) {
}
