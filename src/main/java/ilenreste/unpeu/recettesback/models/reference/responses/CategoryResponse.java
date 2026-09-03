package ilenreste.unpeu.recettesback.models.reference.responses;

/**
 * A category as the API exposes it.
 * <p>
 * {@code normalizedName} deliberately does not cross: it is a storage detail
 * that exists to carry the unique constraint, and a client that started
 * displaying or matching on it would be coupled to our normalization rules.
 */
public record CategoryResponse(String id, String name) {
}
