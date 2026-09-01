package ilenreste.unpeu.recettesback.models.media;

import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;

import java.util.Locale;

/**
 * Which stored size of a media item to serve.
 * <p>
 * Parsed by hand rather than bound as an enum by Spring, because Spring's
 * default enum conversion is case-sensitive {@code Enum.valueOf} and fails with
 * a generic 500-shaped error that tells the caller nothing. {@link #from} is
 * case-insensitive, accepts the obvious spellings, and names the valid values in
 * its message.
 */
public enum MediaVariant {

    /** The stored image, downscaled to {@code app.media.max-stored-edge}. */
    FULL,

    /**
     * The list-page variant. A recipe list showing twenty covers must not pull
     * twenty full-size images: at roughly 450 KB each that is a 9 MB page to
     * paint twenty 300px cards.
     */
    THUMBNAIL;

    public static MediaVariant from(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
            case "", "full" -> FULL;
            case "thumb", "thumbnail" -> THUMBNAIL;
            default -> throw new InvalidInputException(
                    "Unknown variant '%s'. Valid values are 'full' and 'thumbnail'.".formatted(raw));
        };
    }
}
