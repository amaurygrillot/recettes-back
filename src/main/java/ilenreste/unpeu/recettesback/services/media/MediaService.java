package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.configuration.MediaProperties;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.mappers.media.MediaMapper;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.media.responses.MediaResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The upload and download pipeline for user-supplied images.
 * <p>
 * Every step of {@link #upload} is a gate, and a failure at any of them stores
 * nothing:
 * <ol>
 *   <li><strong>Size</strong> — enforced by the servlet container before a byte
 *       reaches this class ({@code spring.servlet.multipart.max-file-size}).</li>
 *   <li><strong>Format</strong> — by magic bytes only. The declared
 *       {@code Content-Type} and the filename are both attacker-controlled and
 *       are ignored.</li>
 *   <li><strong>Dimensions</strong> — from the header, before decoding, inside
 *       {@link ImageReencoder}.</li>
 *   <li><strong>Re-encode</strong> — so the stored bytes are ours, not the
 *       uploader's.</li>
 *   <li><strong>Write</strong> under a server-generated key, atomically.</li>
 *   <li><strong>Insert</strong> the row, measuring every column from the
 *       artefact of steps 4 and 5. If the insert fails, the files just written
 *       are deleted.</li>
 * </ol>
 */
@Log4j2
@Service
public class MediaService {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final MediaRepository mediaRepository;
    private final MediaStorageService storageService;
    private final ImageReencoder reencoder;
    private final MediaProperties properties;
    private final MediaMapper mediaMapper;

    /**
     * Bounds how many uploads decode at once.
     * <p>
     * Decoding is the memory-hungry step, and Tomcat is happy to run it on every
     * one of its ~200 request threads simultaneously. Subsampling keeps a single
     * decode small; only this keeps a <em>burst</em> of them small. Saturation
     * answers 503 with a {@code Retry-After}, which is the honest status — the
     * request is fine, the server is momentarily busy.
     */
    private final Semaphore processingPermits;

    public MediaService(MediaRepository mediaRepository, MediaStorageService storageService,
                        ImageReencoder reencoder, MediaProperties properties, MediaMapper mediaMapper) {
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
        this.reencoder = reencoder;
        this.properties = properties;
        this.mediaMapper = mediaMapper;
        // Fair, so a burst does not starve the request that has been waiting longest.
        this.processingPermits = new Semaphore(properties.maxConcurrentProcessing(), true);
    }

    public MediaResponse upload(MultipartFile file) {
        byte[] content = readFully(file);
        ImageFormat format = ImageFormat.detect(content)
                .orElseThrow(() -> new InvalidInputException(
                        "Only JPEG and PNG images are accepted. If this is a photo from an iPhone "
                                + "it is probably HEIC; convert it to JPEG before uploading."));
        log.info("Accepting a {} upload of {} bytes", format, content.length);

        ReencodedImage reencoded = reencodeWithinCapacity(content, format);

        String storageId = UUID.randomUUID().toString();
        String storageKey = storageKeyFor(storageId, reencoded.format(), "");
        String thumbnailKey = storageKeyFor(storageId, reencoded.format(), "_thumb");

        storageService.store(storageKey, reencoded.full());
        storageService.store(thumbnailKey, reencoded.thumb());

        try {
            MediaEntity saved = mediaRepository.save(toEntity(reencoded, storageKey, thumbnailKey));
            log.info("Stored media {} as {}x{} {}", saved.getId(),
                    saved.getWidth(), saved.getHeight(), saved.getContentType());
            return mediaMapper.toResponse(saved);
        } catch (RuntimeException exception) {
            // The files are already on disk and no transaction covers them, so unwind by hand -
            // otherwise every failed insert leaves two orphans nothing will ever reference.
            log.error("Rolling back the files written for a media row that could not be inserted", exception);
            storageService.delete(storageKey);
            storageService.delete(thumbnailKey);
            throw exception;
        }
    }

    /**
     * The bytes to serve for one media id and variant.
     *
     * @throws ResourceNotFoundException if there is no such row, <em>or</em> the row
     *                                   exists but its file does not. The second case is real:
     *                                   restoring a database newer than the media directory
     *                                   leaves rows pointing at missing files, and that must be
     *                                   a 404 rather than a 500
     */
    public StoredMedia load(String id, MediaVariant variant) {
        MediaEntity media = mediaRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("media", id));

        String key = variant == MediaVariant.THUMBNAIL ? media.getThumbnailStorageKey() : media.getStorageKey();
        Resource resource = storageService.load(key)
                .orElseThrow(() -> ResourceNotFoundException.of("media file for", id));

        String extension = ImageFormat.fromContentType(media.getContentType()).extension();
        return new StoredMedia(resource, media.getContentType(),
                "%s.%s".formatted(media.getId(), extension));
    }

    private ReencodedImage reencodeWithinCapacity(byte[] content, ImageFormat format) {
        boolean acquired;
        try {
            acquired = processingPermits.tryAcquire(properties.processingTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceOverloadedException("Image processing was interrupted. Please try again.",
                    properties.processingTimeout());
        }
        if (!acquired) {
            log.warn("Image processing is saturated; refusing an upload after waiting {}",
                    properties.processingTimeout());
            throw new ServiceOverloadedException("The server is busy processing images. Please try again shortly.",
                    properties.processingTimeout());
        }
        try {
            return reencoder.reencode(content, format);
        } finally {
            processingPermits.release();
        }
    }

    /**
     * Shards as {@code ab/cd/<uuid><suffix>.<ext>} so no single directory ends up
     * holding every image on the server, which is where filesystem listings and
     * backups start to hurt.
     */
    private String storageKeyFor(String storageId, ImageFormat format, String suffix) {
        return "%s/%s/%s%s.%s".formatted(
                storageId.substring(0, 2), storageId.substring(2, 4), storageId, suffix, format.extension());
    }

    private MediaEntity toEntity(ReencodedImage reencoded, String storageKey, String thumbnailKey) {
        MediaEntity media = new MediaEntity();
        media.setStorageKey(storageKey);
        media.setThumbnailStorageKey(thumbnailKey);
        // Every column below is measured from what we wrote, never from what was uploaded.
        media.setContentType(reencoded.format().contentType());
        media.setWidth(reencoded.width());
        media.setHeight(reencoded.height());
        media.setSizeBytes(reencoded.full().length);
        media.setChecksumSha256(sha256(reencoded.full()));
        return media;
    }

    private byte[] readFully(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidInputException("No file was uploaded.");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the uploaded file", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every JVM", exception);
        }
    }
}
