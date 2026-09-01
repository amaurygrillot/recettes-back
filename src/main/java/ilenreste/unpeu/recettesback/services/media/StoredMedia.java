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
 */
public record StoredMedia(Resource resource, String contentType, String filename) {
}
