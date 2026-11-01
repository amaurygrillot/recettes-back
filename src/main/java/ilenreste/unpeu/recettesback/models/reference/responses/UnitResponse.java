package ilenreste.unpeu.recettesback.models.reference.responses;

/**
 * A unit of measure as the API exposes it.
 *
 * @param hidden true when a recipe line must not render the unit ("3 oeufs" rather than "3 pièces d'oeufs")
 */
public record UnitResponse(String id, String name, String abbreviation, boolean hidden) {
}
