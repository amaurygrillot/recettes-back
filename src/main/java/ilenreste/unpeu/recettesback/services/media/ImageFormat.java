package ilenreste.unpeu.recettesback.services.media;

import java.util.Arrays;
import java.util.Optional;

/**
 * The allowlist of image formats this application will decode, identified by
 * their leading bytes.
 * <p>
 * <strong>The declared {@code Content-Type} header and the filename extension
 * are both attacker-controlled and are ignored entirely.</strong> Only the
 * file's own leading bytes decide what it is.
 * <p>
 * The list is short on purpose. Every additional format is another parser
 * running on hostile input inside the JVM, so it is widened only for a concrete
 * need:
 * <ul>
 *   <li><strong>SVG is rejected outright</strong>, and no re-encode makes it
 *       safe while it remains an SVG. It is an XML document that can carry
 *       {@code <script>} and external entity references; served from this
 *       origin it is a stored-XSS primitive. Vector icons, if ever wanted, are
 *       a fixed set shipped with the frontend.</li>
 *   <li><strong>HEIC is rejected</strong> because stock {@code ImageIO} cannot
 *       read it. This is a real usability problem - iPhones shoot HEIC by
 *       default - and the chosen answer is for the frontend to convert to JPEG
 *       before upload, which a browser canvas can do, rather than adding a
 *       server-side decoder.</li>
 *   <li><strong>WebP and AVIF are rejected</strong> for the same reason: no
 *       stock codec.</li>
 * </ul>
 */
public enum ImageFormat {

    JPEG("image/jpeg", "jpg", (byte) 0xFF, (byte) 0xD8, (byte) 0xFF),
    PNG("image/png", "png", (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A);

    private final String contentType;
    private final String extension;
    private final byte[] magic;

    ImageFormat(String contentType, String extension, byte... magic) {
        this.contentType = contentType;
        this.extension = extension;
        this.magic = magic;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /**
     * The format whose magic bytes {@code content} starts with, or empty for
     * anything else - including a file too short to identify.
     */
    public static Optional<ImageFormat> detect(byte[] content) {
        return Arrays.stream(values())
                .filter(format -> format.matches(content))
                .findFirst();
    }

    /**
     * The format that records {@code contentType}. Only ever called with a value
     * this application itself wrote into a {@code media} row, so an unknown one
     * means a corrupted row rather than bad input.
     */
    public static ImageFormat fromContentType(String contentType) {
        return Arrays.stream(values())
                .filter(format -> format.contentType.equals(contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown stored content type: " + contentType));
    }

    private boolean matches(byte[] content) {
        if (content == null || content.length < magic.length) {
            return false;
        }
        return Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }
}
