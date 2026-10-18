package ilenreste.unpeu.recettesback.models.recipes.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The block of text shown above everything else on a recipe.
 * <p>
 * A record with one field rather than a bare {@code String} on the recipe
 * requests, so the section can grow a title or a picture later without every
 * client changing the shape it sends. It mirrors
 * {@code RecipeIntroductionResponse}, which additionally carries the section's id.
 * <p>
 * There is no {@code id} here: the introduction is identified by the recipe it
 * belongs to, and accepting one from the body would invite a client to send an id
 * belonging to somebody else's recipe.
 *
 * <h2>Blank text removes the section, and why it has to</h2>
 * {@code text} is deliberately <strong>not</strong> {@code @NotBlank}. Blank —
 * null, empty, or whitespace — means "this recipe has no introduction", and on an
 * update it deletes the row.
 * <p>
 * That convention is not decoration: without it the section could be added but
 * never removed. Jackson deserialises a missing property and an explicit
 * {@code "introduction": null} to the <em>same</em> {@code Optional.empty()}, so
 * {@code Optional} alone cannot tell "leave it alone" from "delete it", and every
 * partial update that did not mention the introduction would otherwise either
 * wipe it or pin it in place forever. Sending the object with blank text is the
 * one spelling that says "delete" and cannot be confused with silence.
 * <p>
 * No length cap: the field is authored in a WYSIWYG editor and arrives as markup;
 * see {@code RecipeStepEntity} for why the column is {@code text} rather than
 * {@code varchar}.
 */
public record RecipeIntroductionRequest(String text) {

    /**
     * Blank in the {@code String.isBlank} sense, treating a missing value as blank.
     * <p>
     * {@code @JsonIgnore} because this is behaviour, not a field of the body: it looks
     * like a bean getter, so without it the published schema listed a {@code blank}
     * property that clients neither send nor receive.
     */
    @JsonIgnore
    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
