package ilenreste.unpeu.recettesback.services.media;

import lombok.extern.log4j.Log4j2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Reads the single EXIF tag this application needs: the orientation a camera
 * recorded for a JPEG.
 * <p>
 * It exists because {@link javax.imageio.ImageIO} will not give it up. Stock
 * ImageIO exposes the JPEG APP1/Exif segment as an "unknown" marker segment with
 * no accessor for the tag inside it, and re-encoding a phone photo — which strips
 * all metadata by design, since that metadata includes the photographer's GPS
 * coordinates — would therefore silently publish half of them sideways.
 *
 * <h2>Why not a library</h2>
 * This was {@code com.drewnoakes:metadata-extractor}, which is a good library and
 * does far more than this. The review asked whether a dependency was warranted
 * for it, and the answer is no: a dependency is a supply chain, a version to
 * track and a transitive surface, and what was actually needed from it is one
 * two-byte integer at a well-specified offset. The library reads every tag of
 * every metadata format in every container; this reads one tag of one format and
 * is the whole of it.
 *
 * <h2>Safety, since this does parse attacker-controlled bytes</h2>
 * Everything below is bounds-checked against the array it was handed, there is no
 * allocation proportional to anything the file declares, and no recursion — the
 * IFD is walked once, forwards, with each entry's position computed and checked
 * before it is read. The parse never follows an offset backwards or outside the
 * segment. Any inconsistency at all — bad magic, an offset past the end, a type
 * this tag cannot have — returns {@link #NORMAL} rather than throwing, because a
 * missing or malformed EXIF block is the <em>normal</em> case (PNGs have none,
 * and most tools strip it) and must never fail an upload that decoded perfectly
 * well. The worst outcome of being wrong here is a photo displayed sideways.
 * <p>
 * A value that is present and structurally sound but <em>outside</em> the
 * specified range 1..8 is returned as read rather than normalised here. What to
 * do with it belongs to the caller that owns the rotation table:
 * {@code ImageReencoder} logs it and leaves the raster alone.
 * <p>
 * Only JPEG is read. PNG can carry an {@code eXIf} chunk since 2017 but nothing
 * in practice writes orientation into one — phones shoot JPEG or HEIC — so the
 * code for it would be untested-in-anger code guarding a case that does not
 * happen.
 *
 * @see <a href="https://www.cipa.jp/std/documents/e/DC-008-Translation-2019-E.pdf">CIPA DC-008,
 *      the Exif specification; orientation is tag 0x0112 in IFD0</a>
 */
@Log4j2
final class ExifOrientation {

    /** "The 0th row is the visual top": no rotation needed, and the answer when we cannot tell. */
    static final int NORMAL = 1;

    private static final int MARKER_PREFIX = 0xFF;
    private static final int MARKER_APP1 = 0xE1;
    private static final int MARKER_SOS = 0xDA;
    private static final int MARKER_EOI = 0xD9;
    /** Markers that stand alone, with no length and no payload: TEM and RST0..RST7, plus SOI. */
    private static final int STANDALONE_FIRST = 0xD0;
    private static final int STANDALONE_LAST = 0xD8;
    private static final int MARKER_TEM = 0x01;

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};
    private static final int TIFF_MAGIC = 42;
    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;

    private ExifOrientation() {
    }

    /**
     * The orientation recorded in {@code source}, or {@link #NORMAL} when there is
     * none to be had.
     *
     * @param source the uploaded bytes, exactly as received
     */
    static int of(byte[] source) {
        if (source == null || source.length < 4
                || (source[0] & 0xFF) != MARKER_PREFIX || (source[1] & 0xFF) != 0xD8) {
            return NORMAL;
        }

        // Walk the marker segments from just after SOI. Every segment but the standalone ones
        // carries its own length, so this never has to guess how far to jump.
        int offset = 2;
        while (offset + 4 <= source.length) {
            if ((source[offset] & 0xFF) != MARKER_PREFIX) {
                log.debug("Expected a JPEG marker at offset {}; giving up on EXIF", offset);
                return NORMAL;
            }
            int marker = source[offset + 1] & 0xFF;

            // A run of 0xFF bytes before a marker is legal padding.
            if (marker == MARKER_PREFIX) {
                offset++;
                continue;
            }
            // Past SOS the rest of the file is entropy-coded scan data, and EXIF is always before
            // it. Walking into it would be reading compressed pixels as if they were markers.
            if (marker == MARKER_SOS || marker == MARKER_EOI) {
                return NORMAL;
            }
            if (marker == MARKER_TEM || (marker >= STANDALONE_FIRST && marker <= STANDALONE_LAST)) {
                offset += 2;
                continue;
            }

            int segmentLength = ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
            // The length field counts itself, so anything under 2 is nonsense and would loop here.
            if (segmentLength < 2 || offset + 2 + segmentLength > source.length) {
                return NORMAL;
            }
            int payload = offset + 4;
            int payloadLength = segmentLength - 2;

            if (marker == MARKER_APP1 && startsWithExifHeader(source, payload, payloadLength)) {
                return orientationInTiffBlock(source, payload + EXIF_HEADER.length,
                        payloadLength - EXIF_HEADER.length);
            }
            offset += 2 + segmentLength;
        }
        return NORMAL;
    }

    private static boolean startsWithExifHeader(byte[] source, int payload, int payloadLength) {
        if (payloadLength < EXIF_HEADER.length) {
            return false;
        }
        return Arrays.equals(source, payload, payload + EXIF_HEADER.length,
                EXIF_HEADER, 0, EXIF_HEADER.length);
    }

    /**
     * Finds tag {@code 0x0112} in IFD0 of the TIFF block that an Exif APP1 segment
     * wraps.
     * <p>
     * The block is self-describing and little- or big-endian depending on its
     * first two bytes ({@code II} for Intel, {@code MM} for Motorola) — both occur
     * in the wild, which is the one real subtlety here: a reader that assumes one
     * of them silently reads the orientation of half the cameras on the market as
     * a number in the thousands.
     * <p>
     * All offsets inside the block are relative to its own start, which is why
     * {@code tiffStart} is threaded through rather than the array being copied.
     */
    private static int orientationInTiffBlock(byte[] source, int tiffStart, int length) {
        if (length < 8) {
            return NORMAL;
        }
        ByteOrder order = byteOrderAt(source, tiffStart);
        if (order == null) {
            return NORMAL;
        }
        ByteBuffer block = ByteBuffer.wrap(source).order(order);
        int end = tiffStart + length;

        if ((block.getShort(tiffStart + 2) & 0xFFFF) != TIFF_MAGIC) {
            return NORMAL;
        }
        long declaredIfdOffset = block.getInt(tiffStart + 4) & 0xFFFFFFFFL;
        long firstIfd = tiffStart + declaredIfdOffset;
        // Must point forward of the 8-byte header and leave room for the entry count.
        if (declaredIfdOffset < 8 || firstIfd + 2 > end) {
            return NORMAL;
        }
        int ifd = (int) firstIfd;
        int entries = block.getShort(ifd) & 0xFFFF;

        for (int entry = 0; entry < entries; entry++) {
            int at = ifd + 2 + entry * 12;
            if (at + 12 > end) {
                return NORMAL;
            }
            if ((block.getShort(at) & 0xFFFF) != TAG_ORIENTATION) {
                continue;
            }
            // A SHORT's two value bytes sit at the front of the four-byte value field, which is
            // what makes this endianness-dependent rather than just "the first two bytes".
            return switch (block.getShort(at + 2) & 0xFFFF) {
                case TYPE_SHORT -> block.getShort(at + 8) & 0xFFFF;
                case TYPE_LONG -> block.getInt(at + 8);
                // Orientation is a SHORT in the specification. Anything else is a file disagreeing
                // with the specification about its own tag, which is not a value worth trusting.
                default -> NORMAL;
            };
        }
        return NORMAL;
    }

    /** {@code null} for anything that is not one of the two legal byte-order marks. */
    private static ByteOrder byteOrderAt(byte[] source, int tiffStart) {
        byte first = source[tiffStart];
        byte second = source[tiffStart + 1];
        if (first == 'I' && second == 'I') {
            return ByteOrder.LITTLE_ENDIAN;
        }
        if (first == 'M' && second == 'M') {
            return ByteOrder.BIG_ENDIAN;
        }
        return null;
    }
}
