package ilenreste.unpeu.recettesback.models.recipes.responses;

/**
 * Who wrote a recipe.
 * <p>
 * Username only. The email and the enabled flag never cross - every recipe read
 * is public, so anything in here is world-readable.
 */
public record RecipeAuthorResponse(String id, String username) {
}
