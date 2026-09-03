package ilenreste.unpeu.recettesback.models.recipes.responses;

import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;

import java.time.Instant;
import java.util.List;

/**
 * A whole recipe.
 * <p>
 * {@code coverPictures}, {@code ingredientGroups} and {@code steps} arrive in
 * {@code position} order and the array order is the contract — the mapper
 * <strong>iterates</strong> the already-ordered collections and never re-sorts
 * or re-derives. {@code categories} and {@code tags} have no position and are
 * sorted by name instead, so the output is at least stable between two identical
 * requests.
 */
public record RecipeResponse(
        String id,
        String title,
        String recommendations,
        RecipeAuthorResponse author,
        List<CategoryResponse> categories,
        List<TagResponse> tags,
        List<RecipePictureResponse> coverPictures,
        List<RecipeIngredientGroupResponse> ingredientGroups,
        List<RecipeStepResponse> steps,
        Instant createdAt,
        Instant updatedAt
) {
}
