package ilenreste.unpeu.recettesback.models.media;

import java.util.List;

/**
 * What kind of thing a media row holds, and the file extensions that kind comes
 * in.
 * <p>
 * Only {@link #PHOTO} can be produced today — {@code ImageFormat} accepts JPEG
 * and PNG and nothing else — so this column is, right now, a constant. It is
 * stored anyway, and the reasoning is worth writing down because "don't add a
 * column you cannot populate" is normally the right instinct.
 *
 * <h2>Why it lives in {@code models} and not in {@code entities}</h2>
 * It is not an entity: no {@code @Entity}, no {@code @Id}, no table. It is a
 * value, and it is a value that <strong>crosses the HTTP boundary</strong> — it
 * is a documented field of {@code MediaResponse}, which is what tells a client
 * whether to render an {@code <img>} or a {@code <video>}. That is the test
 * {@code models} exists for, and {@link MediaVariant}, the other media enum on
 * the wire, is already its neighbour here. {@code MediaEntity} persisting it with
 * {@code @Enumerated} does not make it an entity any more than persisting a
 * {@code String} makes {@code String} one.
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
 *
 * <h2>Extensions</h2>
 * Each kind declares the extensions it can be stored as, so "what sort of file
 * is this?" is answerable from the kind alone rather than only from a
 * {@code content_type} string. The <em>exact</em> extension of one row stays a
 * property of its format — {@code ImageFormat.JPEG.extension()} — because a kind
 * covers several and a row is exactly one; what the kind holds is the set.
 * <p>
 * The two lists cannot drift apart silently: every {@code ImageFormat} names the
 * {@code MediaType} it belongs to, and {@code ImageFormatTest} asserts
 * that each format's own extension is one this kind declares. Add a format
 * without listing its extension here and that test fails.
 * <p>
 * The <em>format</em> within a kind — JPEG versus PNG — is also recorded per row,
 * as {@code content_type}. It is stored as the media type string rather than an
 * enum ordinal because it is served straight back as a {@code Content-Type}
 * header; {@code ImageFormat} is the typed view of it.
 */
public enum MediaType {

    /** A still image. JPEG or PNG; the exact format of one row is in {@code content_type}. */
    PHOTO("jpg", "png");

    private final List<String> extensions;

    MediaType(String... extensions) {
        this.extensions = List.of(extensions);
    }

    /**
     * Every file extension this kind can be stored as, lower-case and without the
     * leading dot. Immutable, and in declaration order, which is what lets a
     * message built from it read the same way twice.
     */
    public List<String> extensions() {
        return extensions;
    }
}
