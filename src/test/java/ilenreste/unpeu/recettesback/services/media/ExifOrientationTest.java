package ilenreste.unpeu.recettesback.services.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader that replaced {@code metadata-extractor}.
 * <p>
 * {@code ImageReencoderTest} already proves all eight orientations end up with
 * the pixels in the right corner; what it cannot show is that every
 * <em>malformed</em> block is survived rather than thrown on, and that both TIFF
 * byte orders are read. Those are this class's job, because they are the cases a
 * hand-written parser gets wrong and a library would have handled for us.
 */
class ExifOrientationTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void readsEveryOrientationFromALittleEndianBlock(int orientation) {
        byte[] jpeg = TestImages.jpegWithExif(40, 20, orientation, "none", ByteOrder.LITTLE_ENDIAN);

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(orientation);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void readsEveryOrientationFromABigEndianBlock(int orientation) {
        // "MM" blocks are what Nikon and Apple write. A reader that assumed "II" would read a
        // perfectly ordinary photo's orientation 6 as 1536 and leave the photo sideways.
        byte[] jpeg = TestImages.jpegWithExif(40, 20, orientation, "none", ByteOrder.BIG_ENDIAN);

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(orientation);
    }

    @Test
    void readsOrientationStoredAsALong() {
        // Out of specification - orientation is a SHORT - but harmless to accept, and some older
        // encoders do write it this way.
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        tiffWithOrientation(6, 4))));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(6);
    }

    @Test
    void ignoresAnOrientationWhoseTypeIsNeitherShortNorLong() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        tiffWithOrientation(6, 2))));          // ASCII

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void returnsAnOutOfRangeValueAsRead_leavingTheDecisionToTheCaller() {
        // ImageReencoder is what logs it and leaves the raster alone; normalising here would hide
        // a file worth noticing in the log.
        byte[] jpeg = TestImages.jpegWithExif(40, 20, 99, "none");

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(99);
    }

    @Test
    void readsNothingFromAJpegWithNoExifSegment() {
        assertThat(ExifOrientation.of(TestImages.jpeg(40, 20))).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void readsNothingFromAPng() {
        // PNG can carry an eXIf chunk and nothing in practice does. It must not be mistaken for a
        // JPEG and walked as one.
        assertThat(ExifOrientation.of(TestImages.png(40, 20, false))).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void readsNothingFromBytesTooShortToBeAnything() {
        assertThat(ExifOrientation.of(null)).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(new byte[0])).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void skipsAnApp1SegmentThatIsNotExif() {
        // XMP also lives in APP1, identified by a different header. Parsing its XML as a TIFF block
        // is exactly the confusion the six-byte check exists to prevent.
        byte[] xmp = TestImages.app1Segment(
                "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(StandardCharsets.ISO_8859_1));
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20), xmp);

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void findsExifBehindAnEarlierSegment() {
        // A real file has APP0/JFIF first. The walk has to use each segment's declared length to
        // step over it rather than scanning for the next 0xFF, which would land inside a payload.
        byte[] app0 = new byte[]{(byte) 0xFF, (byte) 0xE0, 0, 4, 'J', 'F'};
        byte[] withExif = TestImages.jpegWithExif(40, 20, 6, "none");
        byte[] jpeg = TestImages.jpegWithSegment(withExif, app0);

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(6);
    }

    @Test
    void survivesASegmentWhoseLengthIsNonsense() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                new byte[]{(byte) 0xFF, (byte) 0xE1, 0, 0});    // a length that counts less than itself

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesASegmentThatClaimsToRunPastTheEndOfTheFile() {
        byte[] truncated = TestImages.concat(new byte[]{(byte) 0xFF, (byte) 0xD8},
                new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) 0x7F, (byte) 0xFF, 'E', 'x'});

        assertThat(ExifOrientation.of(truncated)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesAnExifBlockWithNoByteOrderMark() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        new byte[]{'X', 'X', 0, 42, 0, 0, 0, 8, 0, 0})));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesAnExifBlockWithTheWrongTiffMagic() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        new byte[]{'M', 'M', 0, 41, 0, 0, 0, 8, 0, 0})));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesAnIfdOffsetPointingOutsideTheBlock() {
        // The one place a crafted file could ask the reader to read somewhere it should not.
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        new byte[]{'M', 'M', 0, 42, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0})));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesAnIfdOffsetPointingBackIntoItsOwnHeader() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        new byte[]{'M', 'M', 0, 42, 0, 0, 0, 2, 0, 0})));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesADirectoryClaimingMoreEntriesThanItHolds() {
        // Entry count 1000 in a block holding one. The walk must stop at the end of the bytes it
        // was handed, not at the number the file asked it to trust.
        ByteBuffer tiff = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M');
        tiff.putShort((short) 42);
        tiff.putInt(8);
        tiff.putShort((short) 1000);
        tiff.putShort((short) 0x010E);          // ImageDescription, not orientation
        tiff.putShort((short) 2);
        tiff.putInt(1);
        tiff.putInt(0);
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(), tiff.array())));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void readsNothingPastTheStartOfScanData() {
        // Everything after SOS is compressed pixels, and EXIF is always before it. Reading on would
        // be interpreting entropy-coded data as marker segments.
        byte[] jpeg = TestImages.concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA},
                TestImages.jpegWithExif(40, 20, 6, "none"));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void toleratesFillBytesBeforeAMarker() {
        // A run of 0xFF before a marker is legal padding, and must not be read as a marker of its
        // own - which would make the next two bytes a bogus length.
        byte[] padded = TestImages.jpegWithSegment(TestImages.jpegWithExif(40, 20, 3, "none"),
                new byte[]{(byte) 0xFF, (byte) 0xFF});

        assertThat(ExifOrientation.of(padded)).isEqualTo(3);
    }

    @Test
    void stepsOverAStandaloneMarkerToReachTheExifBehindIt() {
        // RST and TEM markers carry no length and no payload. Reading two bytes of length after one
        // would put the walk at a nonsense offset and lose the APP1 segment sitting right after it.
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpegWithExif(40, 20, 8, "none"),
                new byte[]{(byte) 0xFF, (byte) 0xD0});

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(8);
    }

    @Test
    void stopsWhenAMarkerPositionHoldsSomethingOtherThanAMarker() {
        // Either the file is malformed or the walk has lost its place. Guessing forward from here
        // means scanning arbitrary bytes for something that looks like a tag.
        byte[] notMarkers = {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34, 0x56, 0x78};

        assertThat(ExifOrientation.of(notMarkers)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void readsNothingFromAFileThatRunsOutBeforeAnyExif() {
        // SOI and one complete APP0, then nothing: the walk reaches the end of the bytes rather
        // than an SOS or an APP1, and must simply stop.
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 'J', 'F'};

        assertThat(ExifOrientation.of(truncated)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void skipsAnApp1TooShortToCarryTheExifHeader() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment("Exi".getBytes(StandardCharsets.US_ASCII)));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void survivesAnExifSegmentTooShortToHoldATiffHeader() {
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(),
                        new byte[]{'M', 'M', 0, 42})));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void readsNothingFromADirectoryThatCarriesNoOrientationTag() {
        // The ordinary case for a photo whose EXIF holds only a timestamp: every entry is walked,
        // none is tag 0x0112, and the answer is "no rotation" rather than whatever the last entry
        // happened to contain.
        ByteBuffer tiff = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M');
        tiff.putShort((short) 42);
        tiff.putInt(8);
        tiff.putShort((short) 1);               // one entry
        tiff.putShort((short) 0x0132);          // DateTime
        tiff.putShort((short) 2);
        tiff.putInt(1);
        tiff.putInt(0);
        byte[] jpeg = TestImages.jpegWithSegment(TestImages.jpeg(40, 20),
                TestImages.app1Segment(TestImages.concat(TestImages.exifHeader(), tiff.array())));

        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    /** One IFD0 entry holding the orientation with a chosen TIFF type, big-endian. */
    private byte[] tiffWithOrientation(int orientation, int type) {
        ByteBuffer tiff = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M');
        tiff.putShort((short) 42);
        tiff.putInt(8);
        tiff.putShort((short) 1);               // one entry
        tiff.putShort((short) 0x0112);          // Orientation
        tiff.putShort((short) type);
        tiff.putInt(1);                         // one value
        tiff.putInt(type == 4 ? orientation : orientation << 16);
        return tiff.array();
    }
}
