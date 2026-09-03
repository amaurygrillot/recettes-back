package ilenreste.unpeu.recettesback.services.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import ilenreste.unpeu.recettesback.configuration.MediaProperties;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Decodes an uploaded image and writes out a fresh one.
 * <p>
 * Re-encoding is the centrepiece of the upload threat model, because it means
 * the bytes that reach disk are bytes <strong>this application generated</strong>
 * rather than bytes a user supplied. In one step it neutralises:
 * <ul>
 *   <li><strong>Polyglot files</strong> — a valid JPEG whose trailing bytes are
 *       also a valid PHP script or HTML document. Everything that is not pixel
 *       data is discarded.</li>
 *   <li><strong>EXIF metadata</strong> — phone photos carry GPS coordinates, and
 *       publishing a recipe photo should not publish the photographer's home
 *       address. All metadata is dropped; the orientation tag is read first,
 *       specifically so photos do not come out sideways once it is gone.</li>
 *   <li><strong>Malformed-image parser exploits</strong> — a file crafted against
 *       a browser's decoder never reaches a viewer, because it has to survive
 *       ours first, server-side, in a JVM.</li>
 * </ul>
 */
@Log4j2
@Service
public class ImageReencoder {

    private static final int ORIENTATION_NORMAL = 1;

    private final MediaProperties properties;

    public ImageReencoder(MediaProperties properties) {
        this.properties = properties;
    }

    /**
     * @param source the uploaded bytes, already confirmed to start with
     *               {@code format}'s magic bytes
     * @throws InvalidInputException if the image declares more pixels than we
     *                               will decode, or cannot be decoded at all
     */
    public ReencodedImage reencode(byte[] source, ImageFormat format) {
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidInputException("That file is not a readable %s image."
                        .formatted(format.extension()));
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                BufferedImage decoded = decodeWithinBudget(reader);
                BufferedImage upright = applyExifOrientation(decoded, source);

                BufferedImage full = scaleToLongestEdge(upright, properties.maxStoredEdge());
                BufferedImage thumb = scaleToLongestEdge(full, properties.thumbnailEdge());

                // PNG only when the source actually carries transparency, so ingredient icons keep
                // it; everything else becomes a JPEG, which is far smaller for a photograph.
                ImageFormat output = full.getColorModel().hasAlpha() ? ImageFormat.PNG : ImageFormat.JPEG;

                // Dimensions come from the image that was written, after subsampling, rotation and
                // downscaling - never from the header read in decodeWithinBudget.
                return new ReencodedImage(encode(full, output), encode(thumb, output),
                        output, full.getWidth(), full.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to re-encode an uploaded image", exception);
        }
    }

    /**
     * Reads the dimensions from the header, rejects anything over the caps, then
     * decodes <em>at reduced resolution</em>.
     * <p>
     * The header read is what stops a decompression bomb: a 400 KB PNG can
     * declare 30000x30000 and expand to gigabytes of heap the moment
     * {@code ImageIO.read} is called on it. {@link ImageReader#getWidth} parses
     * the header without allocating the pixel raster.
     * <p>
     * The subsampling is what keeps even a <em>legal</em> upload cheap. Nothing
     * larger than {@code max-stored-edge} is ever kept, so there is no reason to
     * materialise the full raster first: an 8000x6000 photo decodes directly at
     * 2667x2000 (~21 MB) instead of 48 MP (~192 MB). Without it, the dimension
     * cap alone still permits a 40 MP raster per concurrent upload.
     */
    private BufferedImage decodeWithinBudget(ImageReader reader) throws IOException {
        int sourceWidth = reader.getWidth(0);
        int sourceHeight = reader.getHeight(0);

        if (sourceWidth > properties.maxDimension() || sourceHeight > properties.maxDimension()) {
            throw new InvalidInputException("Image is too large: %dx%d, maximum is %d pixels per side."
                    .formatted(sourceWidth, sourceHeight, properties.maxDimension()));
        }
        // Both checks are needed: 8000x8000 passes the per-side cap and is still 64 megapixels.
        long totalPixels = (long) sourceWidth * sourceHeight;
        if (totalPixels > properties.maxPixels()) {
            throw new InvalidInputException("Image is too large: %d megapixels, maximum is %d."
                    .formatted(totalPixels / 1_000_000, properties.maxPixels() / 1_000_000));
        }

        int subsampling = subsamplingFor(Math.max(sourceWidth, sourceHeight));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(subsampling, subsampling, 0, 0);
        log.debug("Decoding a {}x{} image with subsampling {}", sourceWidth, sourceHeight, subsampling);

        return reader.read(0, param);
    }

    /**
     * The largest whole factor that still leaves the longest edge at or above
     * the stored-edge cap, so no detail is lost before the final scale.
     */
    private int subsamplingFor(int longestSourceEdge) {
        return Math.max(1, longestSourceEdge / properties.maxStoredEdge());
    }

    /**
     * Rotates and flips the raster to match the EXIF orientation tag.
     * <p>
     * Stock {@code ImageIO} exposes the JPEG APP1/Exif segment only as an
     * unparsed marker segment with no accessor for this tag, which is the entire
     * reason metadata-extractor is a dependency. Note the rotation can swap width
     * and height — a second reason the header dimensions cannot be reused for the
     * stored row.
     * <p>
     * A missing or unreadable EXIF block is the normal case for PNGs and for
     * anything a tool has already stripped, so it is not an error.
     */
    private BufferedImage applyExifOrientation(BufferedImage image, byte[] source) {
        int orientation = readOrientation(source);
        if (orientation == ORIENTATION_NORMAL) {
            return image;
        }
        log.debug("Applying EXIF orientation {}", orientation);

        int width = image.getWidth();
        int height = image.getHeight();
        boolean swapsAxes = orientation >= 5;

        // Written as explicit matrices rather than chained rotate/scale/translate calls. Each row
        // is directly checkable against the EXIF spec, which defines a value as "the 0th row of the
        // stored image is the <this> side of the displayed image, and the 0th column is <that>":
        //
        //   2 -> (top, right)     3 -> (bottom, right)  4 -> (bottom, left)
        //   5 -> (left, top)      6 -> (right, top)     7 -> (right, bottom)   8 -> (left, bottom)
        //
        // The chained form is how an earlier draft got value 7 wrong - the composition order of
        // rotate-then-scale-then-translate is not obvious, and that one pushed the whole image off
        // the canvas, producing a blank result rather than an error.
        //
        // AffineTransform's constructor takes (m00, m10, m01, m11, m02, m12), so each line below
        // reads as: x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12.
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);       // x' = w-x,  y' = y
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height); // x' = w-x,  y' = h-y
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);      // x' = x,    y' = h-y
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);            // x' = y,    y' = x
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);      // x' = h-y,  y' = x
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width); // x' = h-y,  y' = w-x
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);       // x' = y,    y' = w-x
            default -> null;
        };
        if (transform == null) {
            log.warn("Ignoring an out-of-range EXIF orientation value: {}", orientation);
            return image;
        }

        BufferedImage rotated = blankLike(image, swapsAxes ? height : width, swapsAxes ? width : height);
        Graphics2D graphics = rotated.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, transform, null);
        } finally {
            graphics.dispose();
        }
        return rotated;
    }

    private int readOrientation(byte[] source) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception exception) {
            // Absent, truncated or nonsensical EXIF is routine, not a reason to reject an upload
            // that decoded perfectly well. The photo may come out sideways; that is the worst case.
            log.debug("No usable EXIF orientation on this upload", exception);
        }
        return ORIENTATION_NORMAL;
    }

    /**
     * Scales down to {@code longestEdge}, halving repeatedly first.
     * <p>
     * A single large bicubic step aliases badly past roughly 2x; halving to
     * within one step of the target and finishing there is the standard fix and
     * is what keeps a 640px thumbnail legible. An image already small enough is
     * returned untouched — this never scales <em>up</em>.
     */
    private BufferedImage scaleToLongestEdge(BufferedImage image, int longestEdge) {
        int currentLongest = Math.max(image.getWidth(), image.getHeight());
        if (currentLongest <= longestEdge) {
            return image;
        }
        BufferedImage current = image;
        while (Math.max(current.getWidth(), current.getHeight()) > longestEdge * 2) {
            current = redraw(current, Math.max(1, current.getWidth() / 2), Math.max(1, current.getHeight() / 2));
        }
        double ratio = (double) longestEdge / Math.max(current.getWidth(), current.getHeight());
        return redraw(current,
                Math.max(1, (int) Math.round(current.getWidth() * ratio)),
                Math.max(1, (int) Math.round(current.getHeight() * ratio)));
    }

    private BufferedImage redraw(BufferedImage image, int width, int height) {
        BufferedImage target = blankLike(image, width, height);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * A blank canvas with a normalised colour model: ARGB when the source has
     * transparency, RGB otherwise.
     * <p>
     * Normalising here is what keeps JPEG encoding correct further down. Writing
     * a raster that still carries an alpha channel (or a CMYK/indexed model) as
     * JPEG produces inverted or wildly wrong colours rather than an error.
     */
    private BufferedImage blankLike(BufferedImage source, int width, int height) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        return new BufferedImage(width, height, type);
    }

    private byte[] encode(BufferedImage image, ImageFormat format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format.extension()).next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == ImageFormat.JPEG && param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(properties.jpegQuality());
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
