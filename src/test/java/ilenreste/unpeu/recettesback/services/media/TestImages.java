package ilenreste.unpeu.recettesback.services.media;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Real image bytes for the media tests.
 * <p>
 * Everything here produces genuine files rather than fixtures on disk, so the
 * tests state exactly what they feed the pipeline and a reader can see why each
 * case matters.
 */
final class TestImages {

    private TestImages() {
    }

    /** A JPEG with visible structure, so a downscale that silently blanks the image would show. */
    static byte[] jpeg(int width, int height) {
        return encode(paint(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)), "jpg");
    }

    /** A PNG. With {@code alpha}, it carries a real transparent region. */
    static byte[] png(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        paint(image);
        if (alpha) {
            for (int x = 0; x < width / 2; x++) {
                for (int y = 0; y < height / 2; y++) {
                    image.setRGB(x, y, 0x00000000);
                }
            }
        }
        return encode(image, "png");
    }

    /**
     * A JPEG carrying an EXIF APP1 segment with an orientation tag and an
     * {@code ImageDescription} string.
     * <p>
     * The description stands in for the metadata a real phone photo carries — GPS
     * coordinates, camera serial, timestamps — so a test can assert by substring
     * that re-encoding actually drops it rather than trusting that it does.
     */
    static byte[] jpegWithExif(int width, int height, int orientation, String description) {
        byte[] base = jpeg(width, height);
        byte[] app1 = exifApp1Segment(orientation, description);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, 2);                       // SOI (FF D8)
        out.write(app1, 0, app1.length);             // our APP1, before everything else
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }

    /**
     * A PNG whose IHDR <em>declares</em> the given dimensions while the file
     * itself stays a few dozen bytes — the shape of a decompression bomb.
     * <p>
     * Reaching a pixel raster from this would allocate width x height x 4 bytes;
     * the point of the test using it is that the pipeline rejects it from the
     * header and never gets that far.
     */
    static byte[] pngHeaderClaiming(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width);
        ihdr.putInt(height);
        ihdr.put((byte) 8);     // bit depth
        ihdr.put((byte) 2);     // colour type: truecolour
        ihdr.put((byte) 0);     // compression
        ihdr.put((byte) 0);     // filter
        ihdr.put((byte) 0);     // interlace
        writeChunk(out, "IHDR", ihdr.array());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(first);
        out.writeBytes(second);
        return out.toByteArray();
    }

    static boolean contains(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static BufferedImage paint(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.RED);
        // Asymmetric on purpose: a rotation test can only tell 90 from 270 if the content does.
        graphics.fillRect(0, 0, Math.max(1, image.getWidth() / 4), Math.max(1, image.getHeight() / 8));
        graphics.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * A minimal but valid little-endian TIFF block wrapped in a JPEG APP1
     * segment: two IFD0 entries, orientation inline and the description at an
     * offset past the directory.
     */
    private static byte[] exifApp1Segment(int orientation, String description) {
        byte[] descriptionBytes = (description + "\0").getBytes(StandardCharsets.US_ASCII);

        // TIFF header (8) + entry count (2) + two entries (24) + next-IFD offset (4) = 38
        int descriptionOffset = 38;
        ByteBuffer tiff = ByteBuffer.allocate(descriptionOffset + descriptionBytes.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I');
        tiff.putShort((short) 42);
        tiff.putInt(8);                       // offset of IFD0

        tiff.putShort((short) 2);             // entry count
        tiff.putShort((short) 0x0112);        // Orientation
        tiff.putShort((short) 3);             // SHORT
        tiff.putInt(1);
        tiff.putShort((short) orientation);
        tiff.putShort((short) 0);             // padding to four value bytes
        tiff.putShort((short) 0x010E);        // ImageDescription
        tiff.putShort((short) 2);             // ASCII
        tiff.putInt(descriptionBytes.length);
        tiff.putInt(descriptionOffset);
        tiff.putInt(0);                       // no next IFD
        tiff.put(descriptionBytes);

        byte[] payload = tiff.array();
        int segmentLength = 2 + 6 + payload.length;   // length field + "Exif\0\0" + TIFF block

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xE1);
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.writeBytes("Exif".getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.write(0);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
