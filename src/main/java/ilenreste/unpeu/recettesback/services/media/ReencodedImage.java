package ilenreste.unpeu.recettesback.services.media;

/**
 * The artefact of the re-encode: bytes this application generated, plus the
 * dimensions of the image that actually produced them.
 * <p>
 * {@code width} and {@code height} are read from the {@code BufferedImage} that
 * was written, never from the source header — they differ whenever the source
 * exceeded the stored-edge cap, and EXIF rotation can swap them besides.
 *
 * @param full   the stored image, downscaled to {@code app.media.max-stored-edge}
 * @param thumb  the list-page variant, downscaled to {@code app.media.thumbnail-edge}
 * @param format the format <em>we</em> chose, which decides the recorded content type
 * @param width  width of {@code full}
 * @param height height of {@code full}
 */
public record ReencodedImage(byte[] full, byte[] thumb, ImageFormat format, int width, int height) {
}
