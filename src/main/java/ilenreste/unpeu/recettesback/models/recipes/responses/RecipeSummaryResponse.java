package ilenreste.unpeu.recettesback.models.recipes.responses;

import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code GET /recipes}.
 * <p>
 * Deliberately thin: a listing must not cost one full recipe load per row.
 * Steps, ingredient groups and the pictures' descriptions are all absent.
 *
 * @param coverMediaId  the <em>first</em> cover picture by position, or null.
 *                      "First" is only well-defined because the collection is
 *                      mapped with {@code @OrderBy("position")} — without it the
 *                      thumbnail shown could change between two identical
 *                      requests with no write in between. Fetch it as
 *                      {@code /media/{coverMediaId}?variant=thumbnail}: this is
 *                      exactly the page the thumbnail variant exists for
 * @param coverMediaIds <em>every</em> cover picture, in position order, so a card can
 *                      show them as a small slider. Empty without a cover; otherwise
 *                      its first entry is {@code coverMediaId}. Only ids: a thumbnail
 *                      is fetched per id, and the pictures' descriptions stay on
 *                      {@code GET /recipes/{id}}
 */
public record RecipeSummaryResponse(
        String id,
        String title,
        String coverMediaId,
        List<String> coverMediaIds,
        List<CategoryResponse> categories,
        List<TagResponse> tags,
        RecipeAuthorResponse author,
        Instant createdAt
) {
}
