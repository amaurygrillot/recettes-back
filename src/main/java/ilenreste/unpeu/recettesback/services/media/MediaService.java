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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The upload and download pipeline for user-supplied images.
 * <p>
 * Every step of {@link #intake} is a gate, and a failure at any of them stores
 * nothing:
 * <ol>
 *   <li><strong>Size</strong> — enforced by the servlet container before a byte
 *       reaches this class ({@code spring.servlet.multipart.max-file-size}).</li>
 *   <li><strong>Rate</strong> — how many uploads this account has already made in
 *       the last few minutes, in {@link UploadRateLimiter}; admins are exempt. The only gate that
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
 * </ol>
 * Those are {@link #intake}, and they <strong>create no row</strong>: what the two files become is the caller's
 * decision. {@link #upload} ({@code POST /media}) then inserts a public row, measuring every column from the
 * artefact of the steps above; a list's own photo is attached in the transaction of its list, so that its row is
 * born attached and never exists as an ordinary, publicly readable image (see {@code docs/recipe-list-cover.md}).
 * No transaction covers the filesystem, so what becomes of the two files is decided by {@link #attachInTransaction},
 * the one place both callers go through: they are deleted only when the commit was certainly never attempted.
 * <p>
 * The two file writes share one {@code try}: if either fails, both files are deleted, so the claim above — a
 * failure at any step stores nothing — holds for the thumbnail write as well as for the insert.
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
    private final TransactionTemplate transactions;

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
                        CurrentUserService currentUserService, UploadRateLimiter rateLimiter,
                        TransactionTemplate transactions) {
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
        this.reencoder = reencoder;
        this.properties = properties;
        this.mediaMapper = mediaMapper;
        this.currentUserService = currentUserService;
        this.rateLimiter = rateLimiter;
        this.transactions = transactions;
        // Fair, so a burst does not starve the request that has been waiting longest.
        this.processingPermits = new Semaphore(properties.maxConcurrentProcessing(), true);
    }

    /**
     * {@code POST /media}: the gates and the files of {@link #intake}, then the public row, inserted in a transaction of
     * its own by {@link #attachInTransaction}, which also decides what becomes of the files if that does not complete.
     * The files are deleted only if the commit was certainly never attempted; a commit that fails keeps them, because
     * this row is public and a deleted file under a committed row is an image that answers {@code 404} while a recipe
     * may already reference it.
     */
    public MediaResponse upload(MultipartFile file) {
        StoredImage image = intake(file);
        MediaEntity saved = attachInTransaction(image, "a media upload", () -> {
            MediaEntity row = persist(image);
            // Flushed inside the work, so that an insert the database refuses fails here, where the rollback is
            // certain, and not at the commit, where it looks like a commit that failed.
            mediaRepository.flush();
            return row;
        });
        log.info("Stored media {} as {}x{} {}", saved.getId(), saved.getWidth(), saved.getHeight(), saved.getContentType());
        return mediaMapper.toResponse(saved);
    }

    /**
     * The gates of the pipeline, then both files on disk. <strong>No row, no transaction</strong>: it holds no
     * database connection while an image decodes or while the request waits for a processing permit, which is why a
     * caller that needs a transaction opens it <em>after</em> this returns.
     * <p>
     * Shared by {@code POST /media} and by a list's own photo, which therefore answer the same statuses, are counted
     * by the same rate limit and decode under the same pool.
     *
     * @throws ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException (429) the account's allowance is spent
     * @throws InvalidInputException                                               (400) no file, not an allowed
     *                                                                             image, undecodable or too large
     * @throws ServiceOverloadedException                                          (503) the decode pool is full
     */
    public StoredImage intake(MultipartFile file) {
        // First, and before the bytes are read into the heap: a refusal here must be the cheapest
        // thing this method can do, or the limit protecting the disk becomes a way to spend memory.
        enforceRateLimit();

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

        // Both writes are inside the try. The thumbnail write can fail on its own - a full disk, a
        // directory that turned unwritable between the two calls - and when it did, the full-size
        // file written the line before stayed on disk with no row pointing at it. Nothing would
        // ever find it again: the orphan sweep enumerates media rows, so a file with no row is
        // invisible to it permanently. delete() is best-effort and swallows its own failures, so
        // unwinding a file that was never written is harmless.
        try {
            storageService.store(storageKey, reencoded.full());
            storageService.store(thumbnailKey, reencoded.thumb());
        } catch (RuntimeException exception) {
            log.error("Rolling back the files written for a media upload that could not be completed",
                    exception);
            storageService.delete(storageKey);
            storageService.delete(thumbnailKey);
            throw exception;
        }
        // Every field is measured from what we wrote, never from what was uploaded.
        return new StoredImage(storageKey, thumbnailKey, reencoded.format(), reencoded.width(), reencoded.height(),
                reencoded.full().length, sha256(reencoded.full()));
    }

    /**
     * Inserts the row for the files {@link #intake} wrote. It joins the caller's transaction if there is one, which is
     * how a list's own photo is inserted and attached in a single commit.
     */
    public MediaEntity persist(StoredImage image) {
        MediaEntity media = new MediaEntity();
        // Read off the format rather than hard-coded here: the format is what knows whether it
        // produces a photo or a video, so the day VIDEO exists there is no branch to remember.
        media.setMediaType(image.format().mediaType());
        media.setStorageKey(image.storageKey());
        media.setThumbnailStorageKey(image.thumbnailStorageKey());
        media.setContentType(image.format().contentType());
        media.setWidth(image.width());
        media.setHeight(image.height());
        media.setSizeBytes(image.sizeBytes());
        media.setChecksumSha256(image.checksumSha256());
        return mediaRepository.save(media);
    }

    /**
     * Runs {@code attaching}, the code that gives the files of an {@link #intake} their row, in a new transaction, and
     * settles the files by how that transaction ended. <strong>The files are deleted when, and only when, the commit was
     * certainly never attempted</strong>: the transaction never began, or {@code attaching} threw and the transaction
     * rolled back.
     * <p>
     * « Rolled back » alone is not enough, because it is what the transaction manager reports for a commit that failed
     * at the database too (the connection drops while PostgreSQL commits and the acknowledgement is lost: JPA throws a
     * {@code DataAccessException} and the manager rolls back what it can and says {@code STATUS_ROLLED_BACK}) — and
     * then the server may have committed. So a rollback reported <em>after {@code attaching} returned</em>, like a
     * commit whose outcome is unknown ({@code STATUS_UNKNOWN}), keeps the files and logs a {@code warn}: a deleted file
     * under a committed row is an image that answers {@code 404} for ever, where a stray pair of files is only disk. A
     * commit that completed keeps them as well, whatever is thrown after it.
     * <p>
     * {@code attaching} should therefore flush what it writes before it returns, so that the ordinary failures (a
     * constraint, a lost lock) happen inside it, where the rollback is certain.
     * <p>
     * No transaction may be active: it is the caller of {@link #intake}, which holds no connection while an image
     * decodes, and this opens the short one that attaches the result. A caller inside a transaction would make the
     * template join it, so that the hook below would be told how the <em>outer</em> transaction ended, long after the work
     * returned: a failure of the rest of that transaction would look like a failed commit and keep the files of a
     * certain rollback. That is refused, not documented.
     *
     * @param owner what the files are being attached to, in the words of the log (« recipe list 42 »)
     * @throws IllegalStateException if a transaction is already active, which is a bug in the caller; the work is not
     *                               run, and the files, which will never have a row, are deleted
     */
    public <T> T attachInTransaction(StoredImage image, String owner, Supplier<T> attaching) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Nothing will ever attach them, and no hook is registered to say so.
            discard(image);
            throw new IllegalStateException("The files of " + owner + " must be attached in a transaction of their own, "
                    + "and one is already active");
        }
        NewFiles files = new NewFiles(image, owner);
        try {
            return transactions.execute(status -> {
                // First, so that a failure of anything in the work, a lock included, is still a rollback that removes
                // them. Before the transaction is marked as begun: if registering fails, nothing was written, and the
                // catch below deletes the files.
                TransactionSynchronizationManager.registerSynchronization(files);
                files.transactionBegan();
                T attached = attaching.get();
                files.workReturned();
                return attached;
            });
        } catch (RuntimeException failure) {
            if (!files.hasBegun()) {
                log.warn("The transaction to attach the files {} and {} to {} could not begin: deleting them",
                        image.storageKey(), image.thumbnailStorageKey(), owner);
                discard(image);
            }
            throw failure;
        }
    }

    /**
     * Deletes the files of an {@link #intake} whose row will never exist. Best effort, like every delete of the
     * storage: a file that cannot be deleted is logged, and never stops the other from being tried or fails the
     * caller, who is already unwinding something.
     */
    private void discard(StoredImage image) {
        deleteQuietly(image.storageKey(), "an upload that was never attached");
        deleteQuietly(image.thumbnailStorageKey(), "an upload that was never attached");
    }

    /**
     * The new files of one {@link #attachInTransaction}, and what is known about how it went: whether the transaction
     * began, and whether the work had returned when it ended. Registered as the transaction's own synchronization, so
     * that what is done with the files is decided by how the transaction <em>ended</em> and not by which exception
     * happened to reach a {@code catch}. One thread only: the transaction is bound to the one that runs the attach.
     */
    private final class NewFiles implements TransactionSynchronization {

        private final StoredImage image;
        private final String owner;
        private boolean begun;
        private boolean workReturned;

        private NewFiles(StoredImage image, String owner) {
            this.image = image;
            this.owner = owner;
        }

        void transactionBegan() {
            begun = true;
        }

        void workReturned() {
            workReturned = true;
        }

        boolean hasBegun() {
            return begun;
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
                return;
            }
            if (status == STATUS_ROLLED_BACK && !workReturned) {
                log.warn("The transaction to attach the files {} and {} to {} rolled back: deleting them",
                        image.storageKey(), image.thumbnailStorageKey(), owner);
                discard(image);
                return;
            }
            if (!workReturned) {
                // The work threw and then the rollback itself failed (the connection died): no commit was attempted,
                // so the files are most likely strays, but nobody saw the rollback happen.
                log.warn("The transaction to attach the files {} and {} to {} could not be rolled back after its work "
                                + "threw: keeping them, as the outcome is unknown. No commit was attempted, so they are "
                                + "most likely strays that can be deleted",
                        image.storageKey(), image.thumbnailStorageKey(), owner);
                return;
            }
            String outcome = status == STATUS_ROLLED_BACK
                    ? "was reported rolled back after its work had returned, so the commit itself failed"
                    : "ended with an unknown outcome after its work had returned";
            log.warn("The transaction to attach the files {} and {} to {} {}: keeping them, because its row may have "
                            + "been committed. Look up the storage key {}: a row means they are its files, no row "
                            + "means they are strays that can be deleted",
                    image.storageKey(), image.thumbnailStorageKey(), owner, outcome, image.storageKey());
        }
    }

    /**
     * Deletes the row now, and both files <strong>once the surrounding transaction has committed</strong>, so that a
     * rollback never leaves a row pointing at a deleted file. A file that cannot be deleted is logged by
     * {@link MediaStorageService#delete} with its key. A storage that <em>throws</em> instead is caught and logged here,
     * with the media id: the hook runs after the commit, so nothing it does may fail a request that has committed, and a
     * failure on the first file must not keep the second from being tried.
     * <p>
     * Only for a row nothing else references, which is true of a list's own photo and of nothing else: nothing accepts
     * its id as a recipe's picture or an ingredient's icon.
     *
     * @throws IllegalStateException if no transaction is active, which is a bug in the caller
     */
    public void deleteAfterCommit(MediaEntity media) {
        // The keys are read first, while the row is still there; and the hook is registered before anything is
        // deleted, so that a caller with no transaction fails here and deletes nothing.
        String mediaId = "media " + media.getId();
        String storageKey = media.getStorageKey();
        String thumbnailKey = media.getThumbnailStorageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(storageKey, mediaId);
                deleteQuietly(thumbnailKey, mediaId);
            }
        });
        mediaRepository.delete(media);
    }

    /**
     * One file, and never an exception: for the callers whose decision is already taken (a commit that happened, a
     * rollback that is being undone). One line, no stack trace: the key and the owner are what someone cleaning up needs.
     */
    private void deleteQuietly(String storageKey, String owner) {
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException failure) {
            log.error("Could not delete the file {} of {}: {}", storageKey, owner, failure.toString());
        }
    }

    /**
     * The bytes to serve for one media id and variant, <strong>for a public id</strong>: a list's own photo is not
     * found here, whoever asks, and answers exactly like an id that does not exist.
     *
     * @throws ResourceNotFoundException if there is no such public row, <em>or</em> the row
     *                                   exists but its file does not. The second case is real:
     *                                   restoring a database newer than the media directory
     *                                   leaves rows pointing at missing files, and that must be
     *                                   a 404 rather than a 500
     */
    public StoredMedia load(String id, MediaVariant variant) {
        MediaEntity media = mediaRepository.findPublicById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("media", id));
        return open(media, variant);
    }

    /**
     * The bytes of a row the caller has <strong>already decided may be read</strong>. This does not look at who is
     * asking: {@link #load} decides it for a public id, and the cover service of a list for its own photo.
     *
     * @throws ResourceNotFoundException if the row's file is gone
     */
    public StoredMedia open(MediaEntity media, MediaVariant variant) {
        String key = variant == MediaVariant.THUMBNAIL ? media.getThumbnailStorageKey() : media.getStorageKey();
        Resource resource = storageService.load(key)
                .orElseThrow(() -> ResourceNotFoundException.of("media file for", media.getId()));

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

    /**
     * Counts the upload against the caller's allowance - unless the caller is an admin, who has none.
     * <p>
     * The limit exists to stop an ordinary account filling the disk. An admin can already do far worse than
     * upload fast, and is the one who seeds or migrates a library of images in bulk, which is exactly the
     * traffic a per-account cap would refuse. The identity is still resolved first, so an anonymous caller
     * gets the same 403 as before whatever the role check would have said.
     */
    private void enforceRateLimit() {
        String uploaderId = uploaderId();
        if (currentUserService.isAdmin()) {
            log.debug("Upload from admin {} is not rate limited", uploaderId);
            return;
        }
        rateLimiter.requirePermit(uploaderId);
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
