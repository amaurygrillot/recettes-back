package ilenreste.unpeu.recettesback.models.recipes.requests;

import ilenreste.unpeu.recettesback.validation.SingleEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create a recipe list, or replace its name and emoji. The same body for both, and both fields are
 * required on both: a {@code PUT} replaces, it does not patch.
 * <p>
 * The emoji is required here although the front proposes a default — the back requires a value, and
 * {@link SingleEmoji} decides what a valid one is. {@code null} is valid for that constraint, so
 * {@code @NotNull} is what makes it mandatory.
 * <p>
 * The name limit is checked on the string as sent, before trimming. See docs/recipe-lists.md.
 *
 * @param name  1 to {@value #NAME_MAX_LENGTH} characters once trimmed (the service trims it), unique among the
 *              caller's lists once accents, case and spacing are ignored
 * @param emoji exactly one emoji, at most {@value SingleEmoji#MAX_LENGTH} characters
 */
public record RecipeListRequest(

        @NotBlank
        @Size(min = 1, max = NAME_MAX_LENGTH)
        String name,

        @NotNull
        @SingleEmoji
        String emoji
) {

    /** Also the width of the {@code recipe_lists.name} column. */
    public static final int NAME_MAX_LENGTH = 100;
}
