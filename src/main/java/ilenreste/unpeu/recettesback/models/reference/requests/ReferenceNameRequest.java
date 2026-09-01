package ilenreste.unpeu.recettesback.models.reference.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or rename a category or a tag.
 * <p>
 * One record for both, because the two are structurally identical - the only
 * difference between a category and a tag is a product decision (a taxonomy
 * versus an open-ended facet), not a difference in payload. If they ever diverge,
 * split this.
 */
public record ReferenceNameRequest(

        @NotBlank
        @Size(min = 1, max = 100)
        String name
) {
}
