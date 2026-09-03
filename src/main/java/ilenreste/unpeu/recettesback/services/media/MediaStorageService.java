package ilenreste.unpeu.recettesback.services.media;

import org.springframework.core.io.Resource;

import java.util.Optional;

/**
 * Where image bytes physically live, behind an interface so the backend can
 * change without a schema change.
 * <p>
 * Mirrors the existing {@code MailService}/{@code SmtpMailService} pair - the
 * shape this codebase already uses for "one capability, one swappable backend".
 * {@code FilesystemMediaStorageService} is the implementation today; an
 * {@code S3MediaStorageService} is the obvious future one, and because a storage
 * key is an opaque string it becomes an object key with nothing else to change.
 */
public interface MediaStorageService {

    /**
     * Writes {@code content} under {@code storageKey}, creating any intermediate
     * directories. Must be atomic from a reader's point of view: a crash halfway
     * through must not leave a truncated file that a later request happily
     * serves as a valid image.
     *
     * @throws java.io.UncheckedIOException if the bytes cannot be written
     */
    void store(String storageKey, byte[] content);

    /**
     * The stored bytes, or empty when nothing is stored under that key.
     * <p>
     * Returns a {@link Resource} rather than a {@code byte[]} so the controller
     * can stream it: a list page loading twenty images must not put twenty whole
     * images on the heap. Empty is a normal outcome - a database restored from a
     * newer backup than the media directory has rows pointing at missing files,
     * which must answer 404 rather than 500.
     */
    Optional<Resource> load(String storageKey);

    /**
     * Removes the bytes under {@code storageKey}, doing nothing if they are
     * already gone. Used to roll back the files written by an upload whose
     * database insert then failed.
     */
    void delete(String storageKey);
}
