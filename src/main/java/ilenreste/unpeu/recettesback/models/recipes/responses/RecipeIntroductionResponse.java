package ilenreste.unpeu.recettesback.models.recipes.responses;

/**
 * The recipe's opening block, or absent entirely.
 * <p>
 * {@code RecipeResponse.introduction} is <strong>null</strong> when the author
 * wrote none — not an object with an empty {@code text}. A client renders the
 * block if the field is there and renders nothing if it is not, with no emptiness
 * check to get wrong.
 *
 * @param id the section's own id. It happens to equal the recipe's id — the
 *           introduction shares its primary key with the recipe it belongs to —
 *           but it is exposed as the section's identity rather than left to be
 *           inferred, so an editor or a comment anchor can address this block the
 *           same way it addresses any other
 */
public record RecipeIntroductionResponse(String id, String text) {
}
