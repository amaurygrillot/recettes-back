package ilenreste.unpeu.recettesback.models.recipes.responses;

/**
 * One picture, in order.
 * <p>
 * No {@code position} field: the order of the array IS the contract, and
 * {@code position} is a storage detail. Exposing it would invite a client to
 * sort by it and quietly disagree with the array it was given.
 *
 * @param mediaId bytes at {@code /media/{mediaId}}, thumbnail at
 *                {@code /media/{mediaId}?variant=thumbnail}
 */
public record RecipePictureResponse(String mediaId, String altText) {
}
