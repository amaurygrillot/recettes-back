package ilenreste.unpeu.recettesback.services.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageFormatTest {

    @Test
    void detectsJpegAndPngFromTheirLeadingBytes() {
        assertThat(ImageFormat.detect(TestImages.jpeg(10, 10))).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(TestImages.png(10, 10, false))).contains(ImageFormat.PNG);
    }

    @Test
    void rejectsSvg_whichNoReEncodeCouldEverMakeSafe() {
        // An SVG is an XML document that can carry <script> and external entity references. Served
        // from this origin it is a stored-XSS primitive, and unlike a malformed raster there is no
        // decode-and-rewrite step that neutralises it while it remains an SVG.
        byte[] svg = """
                <svg xmlns="http://www.w3.org/2000/svg"><script>alert(document.cookie)</script></svg>
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(ImageFormat.detect(svg)).isEmpty();
    }

    @Test
    void rejectsFormatsWithNoStockCodec() {
        // HEIC (what an iPhone shoots by default) and WebP. Both are refused deliberately rather
        // than by accident: adding a decoder means another parser on attacker-controlled bytes.
        byte[] heic = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        byte[] webp = "RIFF____WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);

        assertThat(ImageFormat.detect(heic)).isEmpty();
        assertThat(ImageFormat.detect(webp)).isEmpty();
    }

    @Test
    void ignoresWhatTheFileClaimsToBe_becauseOnlyTheBytesAreTrustworthy() {
        // A GIF renamed photo.jpg and posted with Content-Type: image/jpeg. Both of those are
        // attacker-controlled and neither reaches this decision.
        byte[] gifPretendingToBeJpeg = "GIF89a and then some pixels".getBytes(StandardCharsets.US_ASCII);

        assertThat(ImageFormat.detect(gifPretendingToBeJpeg)).isEmpty();
    }

    @Test
    void rejectsInputTooShortToIdentify_withoutReadingPastTheEnd() {
        assertThat(ImageFormat.detect(new byte[]{(byte) 0xFF})).isEmpty();
        assertThat(ImageFormat.detect(new byte[0])).isEmpty();
        assertThat(ImageFormat.detect(null)).isEmpty();
    }

    @Test
    void mapsAStoredContentTypeBackToItsFormat() {
        assertThat(ImageFormat.fromContentType("image/jpeg")).isEqualTo(ImageFormat.JPEG);
        assertThat(ImageFormat.fromContentType("image/png")).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void treatsAnUnknownStoredContentTypeAsACorruptedRow() {
        // Only ever called with a value this application wrote itself, so this is a bug, not input.
        assertThatThrownBy(() -> ImageFormat.fromContentType("image/gif"))
                .isInstanceOf(IllegalStateException.class);
    }
}
