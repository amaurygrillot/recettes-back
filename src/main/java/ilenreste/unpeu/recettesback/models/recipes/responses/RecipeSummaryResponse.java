package ilenreste.unpeu.recettesback.models.recipes.responses;

import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code GET /recipes}.
 * <p>
 * Deliberately thin: a listing must not cost one full recipe load per row.
 * Steps, ingredient groups and the remaining pictures are all absent.
 *
 * @param coverMediaId the <em>first</em> cover picture by position, or null.
 *                     "First" is only well-defined because the collection is
 *                     mapped with {@code @OrderBy("position")} — without it the
 *                     thumbnail shown could change between two identical
 *                     requests with no write in between. Fetch it as
 *                     {@code /media/{coverMediaId}?variant=thumbnail}: this is
 *                     exactly the page the thumbnail variant exists for
 */
public record RecipeSummaryResponse(
        String id,
        String title,
        String coverMediaId,
        List<CategoryResponse> categories,
        List<TagResponse> tags,
        RecipeAuthorResponse author,
        Instant createdAt
) {
}
