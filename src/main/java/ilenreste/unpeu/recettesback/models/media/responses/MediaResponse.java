package ilenreste.unpeu.recettesback.models.media.responses;

/**
 * What {@code POST /media} returns, and what a recipe response embeds for each
 * picture.
 * <p>
 * No URLs: the bytes are always at {@code /media/{id}} and
 * {@code /media/{id}?variant=thumbnail}, so returning them would be the server
 * hardcoding its own routes into every payload.
 *
 * @param id          the media id, which is also the cache key
 * @param contentType the type <em>we</em> encoded, never the client's declared one
 * @param width       width of the stored image, so the frontend can reserve layout space
 * @param height      height of the stored image
 * @param sizeBytes   size of the stored full-size file. The thumbnail's few extra kilobytes are
 *                    not counted here: this answers "how big is this image", not "how much disk
 *                    does this row occupy"
 */
public record MediaResponse(
        String id,
        String contentType,
        int width,
        int height,
        long sizeBytes
) {
}
