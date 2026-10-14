package ilenreste.unpeu.recettesback.models.reference.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or rename a tag.
 * <p>
 * This used to be a {@code ReferenceNameRequest} shared with categories, on the
 * grounds that a tag and a category differed only by product convention. They no
 * longer do: a category carries a parent and a {@code categoryType}, a tag is a
 * flat label. The shared record was split rather than growing a field that is
 * meaningless for half its callers.
 */
public record TagRequest(

        @NotBlank
        @Size(min = 1, max = 100)
        String name
) {
}
