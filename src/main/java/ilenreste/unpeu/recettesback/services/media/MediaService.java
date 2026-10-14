package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.properties.MediaProperties;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.mappers.media.MediaMapper;
import ilenreste.unpeu.recettesback.models.media.MediaType;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.media.responses.MediaResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The upload and download pipeline for user-supplied images.
 * <p>
 * Every step of {@link #upload} is a gate, and a failure at any of them stores
 * nothing:
 * <ol>
 *   <li><strong>Size</strong> — enforced by the servlet container before a byte
 *       reaches this class ({@code spring.servlet.multipart.max-file-size}).</li>
 *   <li><strong>Rate</strong> — how many uploads this account has already made in
 *       the last few minutes, in {@link UploadRateLimiter}. The only gate that
 *       looks at anything beyond the one file in hand, and therefore the only one
 *       that can see a thousand perfectly valid uploads filling a disk.</li>
 *   <li><strong>Format</strong> — by magic bytes only. The declared
 *       {@code Content-Type} and the filename are both attacker-controlled and
 *       are ignored.</li>
 *   <li><strong>Dimensions</strong> — from the header, before decoding, inside
 *       {@link ImageReencoder}.</li>
 *   <li><strong>Re-encode</strong> — so the stored bytes are ours, not the
 *       uploader's.</li>
 *   <li><strong>Write</strong> under a server-generated key, atomically.</li>
 *   <li><strong>Insert</strong> the row, measuring every column from the
 *       artefact of steps 4 and 5.</li>
 * </ol>
 * Steps 6 and 7 share one {@code try}: if either file write <em>or</em> the
 * insert fails, every file already written is deleted, so the claim above —
 * a failure at any step stores nothing — holds for the thumbnail write too and
 * not only for the insert.
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
    private final CurrentUserService currentUserService;
    private final UploadRateLimiter rateLimiter;

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
                        ImageReencoder reencoder, MediaProperties properties, MediaMapper mediaMapper,
                        CurrentUserService currentUserService, UploadRateLimiter rateLimiter) {
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
        this.reencoder = reencoder;
        this.properties = properties;
        this.mediaMapper = mediaMapper;
        this.currentUserService = currentUserService;
        this.rateLimiter = rateLimiter;
        // Fair, so a burst does not starve the request that has been waiting longest.
        this.processingPermits = new Semaphore(properties.maxConcurrentProcessing(), true);
    }

    public MediaResponse upload(MultipartFile file) {
        // First, and before the bytes are read into the heap: a refusal here must be the cheapest
        // thing this method can do, or the limit protecting the disk becomes a way to spend memory.
        rateLimiter.requirePermit(uploaderId());

        byte[] content = readFully(file);
        ImageFormat format = ImageFormat.detect(content)
                .orElseThrow(() -> new InvalidInputException(
                        "Only %s images are accepted. If this is a photo from an iPhone it is "
                                .formatted(acceptedExtensions())
                                + "probably HEIC; convert it to JPEG before uploading."));
        log.info("Accepting a {} upload of {} bytes", format, content.length);

        ReencodedImage reencoded = reencodeWithinCapacity(content, format);

        String storageId = UUID.randomUUID().toString();
        String storageKey = storageKeyFor(storageId, reencoded.format(), "");
        String thumbnailKey = storageKeyFor(storageId, reencoded.format(), "_thumb");

        // Both writes are inside the try, not just the insert. The thumbnail write can fail on its
        // own - a full disk, a directory that turned unwritable between the two calls - and when it
        // did, the full-size file written the line before stayed on disk with no row pointing at
        // it. Nothing would ever find it again: the orphan sweep enumerates media rows, so a file
        // with no row is invisible to it permanently. delete() is best-effort and swallows its own
        // failures, so unwinding a file that was never written is harmless.
        try {
            storageService.store(storageKey, reencoded.full());
            storageService.store(thumbnailKey, reencoded.thumb());

            MediaEntity saved = mediaRepository.save(toEntity(reencoded, storageKey, thumbnailKey));
            log.info("Stored media {} as {}x{} {}", saved.getId(),
                    saved.getWidth(), saved.getHeight(), saved.getContentType());
            return mediaMapper.toResponse(saved);
        } catch (RuntimeException exception) {
            // The files may already be on disk and no transaction covers them, so unwind by hand -
            // otherwise every failed upload leaves orphans nothing will ever reference.
            log.error("Rolling back the files written for a media upload that could not be completed",
                    exception);
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
                "%s.%s".formatted(media.getId(), extension), entityTagFor(media, variant));
    }

    /**
     * A strong ETag for one variant of one row.
     * <p>
     * Built from the checksum of the stored full-size bytes plus the variant name,
     * not from the checksum alone: only the full-size image is hashed at upload,
     * and the thumbnail is a different sequence of bytes served from the same id.
     * Give them the same tag and a cache holding the thumbnail will happily answer
     * a request for the full image with a 304.
     * <p>
     * The checksum makes this a genuine content hash rather than a
     * mtime-and-length guess, which is what lets the tag be strong: two responses
     * with this tag are byte-identical.
     */
    private String entityTagFor(MediaEntity media, MediaVariant variant) {
        return "\"%s-%s\"".formatted(media.getChecksumSha256(), variant.name().toLowerCase(Locale.ROOT));
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
        // Read off the format rather than hard-coded here: the format is what knows whether it
        // produces a photo or a video, so the day VIDEO exists there is no branch to remember.
        media.setMediaType(reencoded.format().mediaType());
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

    /**
     * Who the upload is counted against.
     * <p>
     * {@code SecurityFilterConfig} already requires authentication for
     * {@code POST /media}, so an empty optional here means the route was opened up
     * without this being revisited — a 403 naming the reason, never an unlimited
     * anonymous allowance or a {@link NullPointerException} keyed on null.
     */
    private String uploaderId() {
        return currentUserService.currentUserId()
                .orElseThrow(() -> new ForbiddenOperationException("You must be signed in to upload media."));
    }

    /**
     * {@code "JPG or PNG"} — read off {@link MediaType#PHOTO} rather than spelled
     * out, so widening the allowlist updates the rejection message with it.
     */
    private String acceptedExtensions() {
        return MediaType.PHOTO.extensions().stream()
                .map(extension -> extension.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining(" or "));
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
