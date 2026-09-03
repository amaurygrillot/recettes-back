package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.configuration.MediaProperties;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The re-encode is the centrepiece of the upload threat model, so most of what is asserted here is
 * a security property rather than a feature.
 */
class ImageReencoderTest {

    private static final int MAX_STORED_EDGE = 200;
    private static final int THUMBNAIL_EDGE = 64;

    private final MediaProperties properties = new MediaProperties(
            Path.of("target", "test-media"), 8000, 40_000_000L,
            MAX_STORED_EDGE, THUMBNAIL_EDGE, 0.85f, 4, Duration.ofSeconds(10));

    private final ImageReencoder reencoder = new ImageReencoder(properties);

    private BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void downscalesAboveTheCap_andRecordsThePostDownscaleDimensions() throws IOException {
        // The named regression from docs/media-storage.md: the pipeline reads dimensions twice, and
        // the header values sitting in a local variable are the natural ones to reuse. Reusing them
        // makes a 600x300 source stored at 200x100 record 600x300, and every layout the frontend
        // computes from that column is then wrong by 3x for every photo above the cap.
        ReencodedImage result = reencoder.reencode(TestImages.jpeg(600, 300), ImageFormat.JPEG);

        assertThat(result.width()).isEqualTo(MAX_STORED_EDGE);
        assertThat(result.height()).isEqualTo(100);
        assertThat(read(result.full()).getWidth()).isEqualTo(result.width());
        assertThat(read(result.full()).getHeight()).isEqualTo(result.height());
    }

    @Test
    void producesAThumbnailAtTheThumbnailEdge() throws IOException {
        ReencodedImage result = reencoder.reencode(TestImages.jpeg(600, 300), ImageFormat.JPEG);

        BufferedImage thumb = read(result.thumb());
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(THUMBNAIL_EDGE);
        // The whole point of the variant: a list page must not pull the full-size bytes.
        assertThat(result.thumb().length).isLessThan(result.full().length);
    }

    @Test
    void leavesAnImageSmallerThanTheCapAtItsOwnSize_ratherThanScalingItUp() throws IOException {
        ReencodedImage belowStoredEdge = reencoder.reencode(TestImages.jpeg(80, 40), ImageFormat.JPEG);

        assertThat(belowStoredEdge.width()).isEqualTo(80);
        assertThat(belowStoredEdge.height()).isEqualTo(40);
        // Still above the thumbnail edge, so the thumbnail is genuinely smaller.
        assertThat(read(belowStoredEdge.thumb()).getWidth()).isEqualTo(THUMBNAIL_EDGE);

        // Below both caps: nothing is resized, and in particular nothing is blown up to fill them.
        ReencodedImage belowBoth = reencoder.reencode(TestImages.jpeg(40, 20), ImageFormat.JPEG);

        assertThat(belowBoth.width()).isEqualTo(40);
        assertThat(read(belowBoth.thumb()).getWidth()).isEqualTo(40);
        assertThat(read(belowBoth.thumb()).getHeight()).isEqualTo(20);
    }

    @Test
    void stripsEveryTraceOfExifMetadata() {
        String secret = "GPS-48.8566-2.3522-HOME";
        byte[] source = TestImages.jpegWithExif(300, 150, 1, secret);
        assertThat(TestImages.contains(source, secret))
                .as("the fixture must actually carry the metadata being tested")
                .isTrue();

        ReencodedImage result = reencoder.reencode(source, ImageFormat.JPEG);

        // Publishing a recipe photo must not publish the photographer's home address.
        assertThat(TestImages.contains(result.full(), secret)).isFalse();
        assertThat(TestImages.contains(result.full(), "Exif")).isFalse();
        assertThat(TestImages.contains(result.thumb(), secret)).isFalse();
    }

    /**
     * All eight EXIF orientations, asserted on <em>where the content ends up</em> and not only on
     * the output dimensions.
     * <p>
     * Dimensions alone cannot tell 5 from 6 or 7 from 8 - each pair swaps the axes identically and
     * differs only by a mirror. A transform matrix that is subtly wrong therefore passes a
     * dimensions-only test while publishing every affected photo mirrored, which nobody notices
     * until a recipe photo has text in it.
     * <p>
     * TestImages paints a marker block in the source's top-left corner; each row below says which
     * corner that block must occupy once the orientation has been applied.
     */
    @ParameterizedTest(name = "orientation {0} puts the marker {3}")
    @CsvSource({
            "1, 160, 80, TOP_LEFT",       // as shot
            "2, 160, 80, TOP_RIGHT",      // mirrored horizontally
            "3, 160, 80, BOTTOM_RIGHT",   // rotated 180
            "4, 160, 80, BOTTOM_LEFT",    // mirrored vertically
            "5, 80, 160, TOP_LEFT",       // transposed
            "6, 80, 160, TOP_RIGHT",      // rotated 90 clockwise - what a phone held sideways writes
            "7, 80, 160, BOTTOM_RIGHT",   // transversed
            "8, 80, 160, BOTTOM_LEFT"     // rotated 90 anticlockwise
    })
    void appliesEveryExifOrientation(int orientation, int expectedWidth, int expectedHeight,
                                     String expectedCorner) throws IOException {
        ReencodedImage result = reencoder.reencode(
                TestImages.jpegWithExif(160, 80, orientation, "none"), ImageFormat.JPEG);

        assertThat(result.width()).isEqualTo(expectedWidth);
        assertThat(result.height()).isEqualTo(expectedHeight);
        assertThat(markerCorner(read(result.full()))).isEqualTo(expectedCorner);
    }

    /**
     * Which corner holds the red marker block. Sampled a little inside the corner and compared
     * loosely, because JPEG is lossy and the exact RGB values will not survive a round trip.
     */
    private String markerCorner(BufferedImage image) {
        int insetX = Math.max(1, image.getWidth() / 16);
        int insetY = Math.max(1, image.getHeight() / 16);
        record Corner(String name, int x, int y) {
        }
        List<Corner> corners = List.of(
                new Corner("TOP_LEFT", insetX, insetY),
                new Corner("TOP_RIGHT", image.getWidth() - 1 - insetX, insetY),
                new Corner("BOTTOM_LEFT", insetX, image.getHeight() - 1 - insetY),
                new Corner("BOTTOM_RIGHT", image.getWidth() - 1 - insetX, image.getHeight() - 1 - insetY));

        List<String> reddish = corners.stream()
                .filter(corner -> isReddish(image.getRGB(corner.x(), corner.y())))
                .map(Corner::name)
                .toList();
        assertThat(reddish)
                .as("exactly one corner should carry the marker")
                .hasSize(1);
        return reddish.getFirst();
    }

    private boolean isReddish(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red > 150 && green < 120 && blue < 120;
    }

    @Test
    void toleratesAnOutOfRangeOrientationValue_ratherThanFailingTheUpload() {
        ReencodedImage result = reencoder.reencode(
                TestImages.jpegWithExif(160, 80, 42, "none"), ImageFormat.JPEG);

        assertThat(result.width()).isEqualTo(160);
        assertThat(result.height()).isEqualTo(80);
    }

    @Test
    void discardsAnythingAppendedAfterTheImageData() {
        // The polyglot case: a valid JPEG whose trailing bytes are also a valid script or HTML
        // document. Browsers and scanners disagree about where an image ends; re-encoding settles
        // it by keeping only the pixels. The marker below stands in for what an attacker would
        // actually append - a real webshell string here gets quarantined by on-access antivirus
        // before the test can run, which is a flaky failure with a very confusing message.
        String marker = "TRAILING-PAYLOAD-THAT-MUST-NOT-SURVIVE";
        byte[] polyglot = TestImages.concat(TestImages.jpeg(120, 120),
                marker.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(TestImages.contains(polyglot, marker)).isTrue();

        ReencodedImage result = reencoder.reencode(polyglot, ImageFormat.JPEG);

        assertThat(TestImages.contains(result.full(), marker)).isFalse();
        assertThat(TestImages.contains(result.thumb(), marker)).isFalse();
    }

    @Test
    void rejectsADecompressionBombFromItsHeader_withoutDecodingIt() {
        // 30000x30000 is a few dozen bytes on the wire and 3.6 GB as a raster. This must be refused
        // from the IHDR alone: reaching ImageIO.read at all is already the failure.
        byte[] bomb = TestImages.pngHeaderClaiming(30_000, 30_000);
        assertThat(bomb.length).isLessThan(100);

        assertThatThrownBy(() -> reencoder.reencode(bomb, ImageFormat.PNG))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void rejectsAnImageWithinThePerSideCapButOverTheTotalPixelCap() {
        // 7000x7000 passes a per-side check of 8000 and is still 49 megapixels - which is why the
        // per-side cap alone is not enough and both gates exist.
        byte[] bomb = TestImages.pngHeaderClaiming(7_000, 7_000);

        assertThatThrownBy(() -> reencoder.reencode(bomb, ImageFormat.PNG))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("megapixels");
    }

    @Test
    void keepsPngWhenTheSourceHasTransparency_soIngredientIconsSurvive() throws IOException {
        ReencodedImage result = reencoder.reencode(TestImages.png(300, 300, true), ImageFormat.PNG);

        assertThat(result.format()).isEqualTo(ImageFormat.PNG);
        assertThat(read(result.full()).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void writesJpegWhenTheSourceHasNoTransparency_evenIfItArrivedAsPng() {
        ReencodedImage result = reencoder.reencode(TestImages.png(300, 300, false), ImageFormat.PNG);

        assertThat(result.format()).isEqualTo(ImageFormat.JPEG);
        assertThat(result.full()[0]).isEqualTo((byte) 0xFF);
        assertThat(result.full()[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void rejectsBytesThatCannotBeDecodedAtAll() {
        byte[] truncated = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};

        assertThatThrownBy(() -> reencoder.reencode(truncated, ImageFormat.JPEG))
                .isInstanceOf(RuntimeException.class);
    }
}
