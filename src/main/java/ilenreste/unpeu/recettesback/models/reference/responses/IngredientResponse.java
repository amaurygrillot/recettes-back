package ilenreste.unpeu.recettesback.models.reference.responses;

/**
 * An ingredient as the API exposes it.
 *
 * @param iconMediaId null when the ingredient has no icon. The bytes are at
 *                    {@code /media/{iconMediaId}}
 */
public record IngredientResponse(String id, String name, String iconMediaId) {
}
