package ilenreste.unpeu.recettesback.entities.media;

/**
 * What kind of thing a media row holds.
 * <p>
 * Only {@link #PHOTO} can be produced today — {@code ImageFormat} accepts JPEG
 * and PNG and nothing else — so this column is, right now, a constant. It is
 * stored anyway, and the reasoning is worth writing down because "don't add a
 * column you cannot populate" is normally the right instinct.
 *
 * <h2>A discriminator column, not a class hierarchy</h2>
 * The alternative shape is {@code MediaEntity} as a supertype with
 * {@code PhotoEntity} and {@code VideoEntity} under it. That was considered and
 * rejected:
 * <ul>
 *   <li>Everything that points at media — recipe cover pictures, step pictures,
 *       ingredient icons — points at <em>media</em>, not at a photo. A class
 *       hierarchy would either make every one of those associations polymorphic
 *       (a discriminator read on every join, which is the column below with extra
 *       steps) or force each to choose a subtype and lose the ability to hold the
 *       other.</li>
 *   <li>The two would share almost every column. {@code storage_key},
 *       {@code content_type}, {@code width}, {@code height}, {@code size_bytes},
 *       {@code checksum}, {@code uploaded_by} are all common; a video adds a
 *       duration and maybe a poster frame. {@code JOINED} inheritance would put a
 *       two-column table behind an extra join on every read, and
 *       {@code SINGLE_TABLE} is this enum with a different annotation on it.</li>
 *   <li>The real cost of video is not the schema. It is transcoding, range
 *       requests, a storage bill measured in gigabytes and a processing step that
 *       cannot run inside a request thread. None of that is made easier or harder
 *       by which of the two shapes the metadata takes.</li>
 * </ul>
 * What the column does buy is that the day a second kind arrives, existing rows
 * already say what they are. Adding a {@code NOT NULL} discriminator to a table
 * full of rows afterwards means a backfill under
 * {@code ddl-auto=update}, which does not write data — so the choice is between
 * one enum now and a hand-written migration later, on a project that has
 * deliberately no migration tool.
 * <p>
 * The <em>format</em> — JPEG versus PNG — is a different question and is already
 * recorded, as {@code content_type}. It is stored as the media type string rather
 * than an enum ordinal because it is served straight back as a
 * {@code Content-Type} header; {@code ImageFormat} is the typed view of it.
 */
public enum MediaType {

    /** A still image. JPEG or PNG; the exact format is in {@code content_type}. */
    PHOTO
}
