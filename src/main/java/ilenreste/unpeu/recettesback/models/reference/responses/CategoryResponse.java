package ilenreste.unpeu.recettesback.models.reference.responses;

import ilenreste.unpeu.recettesback.entities.reference.CategoryType;

/**
 * A category as the API exposes it, flat.
 * <p>
 * {@code normalizedName} deliberately does not cross: it is a storage detail
 * that exists to carry the unique constraint, and a client that started
 * displaying or matching on it would be coupled to our normalization rules.
 *
 * @param categoryType       {@code MAIN} or {@code SUB}, so a client can render a
 *                           root differently without having to compare
 *                           {@code parentCategoryId} to {@code id} itself
 * @param parentCategoryId the direct parent category. <strong>Null for a
 *                           main category</strong>, even though the stored row
 *                           points at itself — the self-reference is a schema
 *                           device that keeps the relation table total, and
 *                           leaking it would make every client write the
 *                           {@code id.equals(parentCategoryId)} check that
 *                           {@code categoryType} exists to spare them
 */
public record CategoryResponse(
        String id,
        String name,
        CategoryType categoryType,
        String parentCategoryId
) {
}
