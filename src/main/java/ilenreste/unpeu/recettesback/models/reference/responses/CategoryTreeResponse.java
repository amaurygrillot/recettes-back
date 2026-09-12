package ilenreste.unpeu.recettesback.models.reference.responses;

import java.util.List;

/**
 * One node of {@code GET /categories/hierarchy}, with its children nested inside
 * it.
 * <p>
 * Nested rather than flat-with-parent-ids because this endpoint exists to render
 * a navigation menu, and a menu is a tree. The flat shape is already available
 * from {@code GET /categories}; returning the same rows twice in the same shape
 * would make the second endpoint pointless.
 * <p>
 * There is no {@code superiorCategoryId} here: nesting already says who the
 * parent is, and a second encoding of the same fact is a second thing that can
 * disagree. There is no {@code categoryType} either — depth 0 is {@code MAIN} by
 * construction.
 *
 * @param subcategories the <em>direct</em> children, each carrying its own, name-sorted.
 *                      Empty for a leaf, never null
 */
public record CategoryTreeResponse(
        String id,
        String name,
        List<CategoryTreeResponse> subcategories
) {
}
