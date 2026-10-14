package ilenreste.unpeu.recettesback.models.reference.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or update a category.
 * <p>
 * Full replacement, not a patch: this same record serves {@code POST} and
 * {@code PUT}, so an absent {@code parentCategoryId} on a {@code PUT} means
 * "this is a main category now", not "leave the parent alone". Re-parenting and
 * promoting to a root are both ordinary edits, and a shape where one of them is
 * unexpressible would force a second endpoint.
 *
 * @param name              the category as it should be displayed. Stored as
 *                          typed; uniqueness is decided on its normalized form,
 *                          so {@code Entrées} collides with {@code entrees}
 * @param parentCategoryId the id of the category this one sits directly under.
 *                          Null, blank, or this category's own id all mean
 *                          <strong>main category</strong> — the relation row is
 *                          then written pointing at itself. Anything else must
 *                          name an existing category that is not a descendant of
 *                          this one, or the request is rejected: a cycle would
 *                          make the hierarchy walk non-terminating
 */
public record CategoryRequest(

        @NotBlank
        @Size(min = 1, max = 100)
        String name,

        String parentCategoryId
) {
}
