package ilenreste.unpeu.recettesback.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything tunable about the media pipeline, in one place.
 * <p>
 * There is deliberately no {@code app.media.max-file-size} mirroring
 * {@code spring.servlet.multipart.max-file-size}: the servlet container rejects
 * an oversized part before a single byte reaches application code, so a second
 * copy of that number would be a value that must agree with another one and
 * eventually will not.
 *
 * @param storagePath             root directory for stored bytes. On the VPS this must be
 *                                <strong>outside</strong> the deployment directory, or a redeploy
 *                                wipes every image
 * @param maxDimension            largest accepted width or height, read from the header before
 *                                decoding
 * @param maxPixels               largest accepted total pixel count. Both halves are needed:
 *                                8000x8000 passes a per-edge check and is still 64 MP, which is
 *                                256 MB of heap as a raster
 * @param maxStoredEdge           longest edge of the stored image after downscaling
 * @param thumbnailEdge           longest edge of the stored thumbnail
 * @param jpegQuality             re-encode quality, 0..1
 * @param maxConcurrentProcessing how many uploads may decode at once. Decoding is the
 *                                memory-hungry step and Tomcat would otherwise run it on every
 *                                request thread simultaneously
 * @param processingTimeout       how long an upload waits for a processing permit before the
 *                                request is answered 503
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(
        Path storagePath,
        int maxDimension,
        long maxPixels,
        int maxStoredEdge,
        int thumbnailEdge,
        float jpegQuality,
        int maxConcurrentProcessing,
        Duration processingTimeout
) {
}
