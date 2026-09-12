package ilenreste.unpeu.recettesback.services.media;

import org.springframework.core.io.Resource;

/**
 * One media file ready to be written to a response.
 *
 * @param resource    the bytes, as a streamable {@link Resource} rather than a
 *                    {@code byte[]} — a list page loading twenty images must not
 *                    put twenty whole images on the heap
 * @param contentType the value recorded at upload time, never sniffed from the
 *                    file at read time
 * @param filename    a name <em>we</em> generated, never anything the uploader
 *                    supplied
 * @param entityTag   a strong validator for this exact variant, derived from the
 *                    stored checksum. It is what lets a client revalidate instead
 *                    of re-downloading, and it is variant-specific — the full
 *                    image and its thumbnail are different bytes at the same id,
 *                    so they must not share a tag or a cache would serve one for
 *                    the other
 */
public record StoredMedia(Resource resource, String contentType, String filename, String entityTag) {
}
