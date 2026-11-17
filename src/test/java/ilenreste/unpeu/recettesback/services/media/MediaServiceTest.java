package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.support.ScriptedTransactionManager;
import ilenreste.unpeu.recettesback.support.TestImages;
import ilenreste.unpeu.recettesback.properties.MediaProperties;
import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import ilenreste.unpeu.recettesback.mappers.media.MediaMapper;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.media.responses.MediaResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    private static final Duration UPLOAD_WINDOW = Duration.ofMinutes(10);

    private MediaRepository mediaRepository;
    private MediaStorageService storageService;
    private CurrentUserService currentUserService;
    private MediaProperties properties;
    private MediaService mediaService;
    private ScriptedTransactionManager transactionManager;

    private MediaProperties properties(int permits, Duration timeout) {
        return properties(permits, timeout, 20);
    }

    private MediaProperties properties(int permits, Duration timeout, int uploadLimit) {
        return new MediaProperties(Path.of("target", "test-media"), 8000, 40_000_000L,
                200, 64, 0.85f, permits, timeout, uploadLimit, UPLOAD_WINDOW);
    }

    private MediaService mediaService(MediaProperties mediaProperties, ImageReencoder reencoder) {
        return mediaService(mediaProperties, reencoder, new MediaMapper());
    }

    private MediaService mediaService(MediaProperties mediaProperties, ImageReencoder reencoder,
                                      MediaMapper mediaMapper) {
        return new MediaService(mediaRepository, storageService, reencoder, mediaProperties,
                mediaMapper, currentUserService,
                new UploadRateLimiter(mediaProperties, Clock.systemUTC()),
                new TransactionTemplate(transactionManager));
    }

    @BeforeEach
    void setUp() {
        transactionManager = new ScriptedTransactionManager();
        mediaRepository = mock(MediaRepository.class);
        storageService = mock(MediaStorageService.class);
        currentUserService = mock(CurrentUserService.class);
        when(currentUserService.currentUserId()).thenReturn(Optional.of("uploader-1"));
        properties = properties(4, Duration.ofSeconds(5));
        mediaService = mediaService(properties, new ImageReencoder(properties));
    }

    private MultipartFile upload(byte[] content) {
        // The declared content type and filename are deliberately wrong: neither may influence
        // anything, because both are attacker-controlled.
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", content);
    }

    @Test
    void storesBothVariants_andRecordsOnlyWhatWeWrote() {
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MediaResponse response = mediaService.upload(upload(TestImages.jpeg(600, 300)));

        ArgumentCaptor<MediaEntity> saved = ArgumentCaptor.forClass(MediaEntity.class);
        verify(mediaRepository).save(saved.capture());
        MediaEntity entity = saved.getValue();

        assertThat(entity.getContentType()).isEqualTo("image/jpeg");
        assertThat(entity.getWidth()).isEqualTo(200);
        assertThat(entity.getHeight()).isEqualTo(100);
        assertThat(entity.getStorageKey()).matches("[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(entity.getThumbnailStorageKey()).endsWith("_thumb.jpg");
        assertThat(entity.getStorageKey()).isNotEqualTo(entity.getThumbnailStorageKey());
        assertThat(response.width()).isEqualTo(200);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storageService, org.mockito.Mockito.times(2)).store(anyString(), stored.capture());
        assertThat(entity.getSizeBytes()).isEqualTo(stored.getAllValues().getFirst().length);
        assertThat(entity.getChecksumSha256()).isEqualTo(sha256(stored.getAllValues().getFirst()));
    }

    @Test
    void rejectsAFileThatIsNotAnAllowedImage_beforeTouchingStorage() {
        MultipartFile notAnImage = upload("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> mediaService.upload(notAnImage))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("JPG or PNG")
                // The HEIC hint matters: an iPhone shoots HEIC by default, so this is the error a
                // real user hits first and it has to tell them what to do about it.
                .hasMessageContaining("HEIC");

        verifyNoInteractions(storageService, mediaRepository);
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(() -> mediaService.upload(upload(new byte[0])))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> mediaService.upload(null))
                .isInstanceOf(InvalidInputException.class);

        verifyNoInteractions(storageService, mediaRepository);
    }

    @Test
    void deletesBothFiles_whenTheRowCannotBeInserted() {
        // No transaction covers the filesystem, so without this every failed insert leaves two
        // orphans that nothing will ever reference or clean up.
        when(mediaRepository.save(any())).thenThrow(new IllegalStateException("constraint"));

        assertThatThrownBy(() -> mediaService.upload(upload(TestImages.jpeg(300, 300))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(storageService, org.mockito.Mockito.times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).hasSize(2)
                .anySatisfy(key -> assertThat(key).endsWith("_thumb.jpg"));
    }

    // ------------------------------------------------------ POST /media: the same rule for the files of a public row

    /**
     * What {@code POST /media} used to do on <em>any</em> exception after its insert: delete the files. A commit that fails
     * at the database says « rolled back » even when the server committed, and the row of an upload is public, so deleting
     * its files leaves an image that answers {@code 404} while a recipe may already reference it. They are kept.
     */
    @Test
    void aCommitThatFailsAfterTheRowWasInserted_keepsTheFilesOfTheUpload() {
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        transactionManager.failingToCommitWith(new DataAccessResourceFailureException("the connection dropped"));

        assertThatThrownBy(() -> mediaService.upload(upload(TestImages.jpeg(300, 300))))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(storageService, org.mockito.Mockito.times(2)).store(anyString(), any(byte[].class));
        verify(storageService, never()).delete(anyString());
    }

    /** The row is committed, and then something throws (here the mapper, which cannot today): the files stay under it. */
    @Test
    void anExceptionAfterTheRowWasCommitted_keepsTheFilesOfTheUpload() {
        MediaMapper failing = mock(MediaMapper.class);
        when(failing.toResponse(any())).thenThrow(new IllegalStateException("the mapper failed"));
        MediaService service = mediaService(properties, new ImageReencoder(properties), failing);
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.upload(upload(TestImages.jpeg(300, 300))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mapper");

        assertThat(transactionManager.commits()).as("the row was committed").isEqualTo(1);
        verify(storageService, never()).delete(anyString());
    }

    /**
     * An insert the database refuses (a constraint) is still a rollback that is <em>certain</em>, so the files still go.
     * That is only so if the row is flushed inside the work: a flush left to the commit would fail after the work had
     * returned, and look like the commit that failed.
     */
    @Test
    void aRowTheDatabaseRefusesToInsert_isFlushedInsideTheWork_soItIsACertainRollbackThatDeletesTheFiles() {
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException("storage_key is not unique")).when(mediaRepository).flush();

        assertThatThrownBy(() -> mediaService.upload(upload(TestImages.jpeg(300, 300))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(storageService, org.mockito.Mockito.times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).anySatisfy(key -> assertThat(key).endsWith("_thumb.jpg"));
    }

    /** The files are written before any transaction exists and the row is inserted in one that is its own. */
    @Test
    void theRowIsInsertedInATransactionOfItsOwn_afterTheFilesAreWritten() {
        when(mediaRepository.save(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("the insert runs in a transaction").isTrue();
            return invocation.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("the files are written while no transaction holds a connection").isFalse();
            return null;
        }).when(storageService).store(anyString(), any(byte[].class));

        mediaService.upload(upload(TestImages.jpeg(300, 300)));

        assertThat(transactionManager.commits()).isEqualTo(1);
    }

    /**
     * The thumbnail write can fail on its own, and when it did the full-size file
     * written the line before stayed on disk with no row pointing at it.
     * <p>
     * Nothing would ever find it again: the orphan sweep enumerates {@code media}
     * rows, so a file with no row is invisible to it permanently. Both {@code store}
     * calls now sit inside the same {@code try} as the insert, which is what makes
     * the class javadoc's claim - a failure at any step stores nothing - true of
     * this step too.
     */
    @Test
    void deletesTheFullSizeFile_whenTheThumbnailWriteFails() {
        org.mockito.Mockito.doThrow(new java.io.UncheckedIOException(
                        new java.io.IOException("No space left on device")))
                .when(storageService).store(argThat(key -> key != null && key.endsWith("_thumb.jpg")),
                        any(byte[].class));

        assertThatThrownBy(() -> mediaService.upload(upload(TestImages.jpeg(300, 300))))
                .isInstanceOf(java.io.UncheckedIOException.class);

        // No row, and no file left behind for it either.
        verify(mediaRepository, never()).save(any());
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(storageService, org.mockito.Mockito.times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues())
                .anySatisfy(key -> assertThat(key).doesNotEndWith("_thumb.jpg"));
    }

    @Test
    void refusesWithRetryAfter_whenEveryProcessingPermitIsHeld() throws Exception {
        // One permit and a timeout short enough to hit: the second caller must be told to retry,
        // not queued indefinitely and not answered 500. This is what bounds peak heap when Tomcat
        // hands two hundred threads an upload each.
        MediaProperties oneAtATime = properties(1, Duration.ofMillis(50));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ImageReencoder blocking = new ImageReencoder(oneAtATime) {
            @Override
            public ReencodedImage reencode(byte[] source, ImageFormat format) {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return super.reencode(source, format);
            }
        };
        MediaService saturated = mediaService(oneAtATime, blocking);
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        byte[] image = TestImages.jpeg(120, 120);
        Thread holder = new Thread(() -> saturated.upload(upload(image)));
        holder.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> saturated.upload(upload(image)))
                    .isInstanceOf(ServiceOverloadedException.class)
                    .hasMessageContaining("busy");
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    @Test
    void refusesAnUploadOverTheAccountsRateLimit_withoutTouchingStorage() {
        // The gate that sees what no single-file check can: one user posting file after file until
        // the disk is full. It is also the first thing upload() does, which is what this asserts by
        // way of storage and the repository staying untouched - a refusal must not cost a decode.
        MediaProperties oneUpload = properties(4, Duration.ofSeconds(5), 1);
        MediaService limited = mediaService(oneUpload, new ImageReencoder(oneUpload));
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        limited.upload(upload(TestImages.jpeg(120, 120)));
        org.mockito.Mockito.clearInvocations(storageService, mediaRepository);

        assertThatThrownBy(() -> limited.upload(upload(TestImages.jpeg(120, 120))))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("limit");

        verifyNoInteractions(storageService, mediaRepository);
    }

    @Test
    void doesNotRateLimitAnAdmin() {
        // Same one-upload allowance as the test above; the second upload goes through because the
        // caller is an admin, not because the limiter was lenient.
        MediaProperties oneUpload = properties(4, Duration.ofSeconds(5), 1);
        MediaService limited = mediaService(oneUpload, new ImageReencoder(oneUpload));
        when(currentUserService.isAdmin()).thenReturn(true);
        when(mediaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        limited.upload(upload(TestImages.jpeg(120, 120)));
        limited.upload(upload(TestImages.jpeg(120, 120)));

        verify(mediaRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void refusesAnUploadWithNoSignedInCaller() {
        // SecurityFilterConfig already requires authentication here, so this is the second lock on
        // the same door: there is no identity to count the upload against, and an unlimited
        // anonymous allowance would be the one hole in the limit above.
        when(currentUserService.currentUserId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.upload(upload(TestImages.jpeg(120, 120))))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("signed in");

        verifyNoInteractions(storageService, mediaRepository);
    }

    @Test
    void servesTheFullVariantByDefault_andTheThumbnailWhenAsked() {
        MediaEntity media = storedMedia();
        when(mediaRepository.findPublicById("media-1")).thenReturn(Optional.of(media));
        Resource resource = new ByteArrayResource(new byte[]{1});
        when(storageService.load(anyString())).thenReturn(Optional.of(resource));

        StoredMedia full = mediaService.load("media-1", MediaVariant.FULL);
        StoredMedia thumb = mediaService.load("media-1", MediaVariant.THUMBNAIL);

        verify(storageService).load("ab/cd/file.jpg");
        verify(storageService).load("ab/cd/file_thumb.jpg");
        assertThat(full.contentType()).isEqualTo("image/jpeg");
        // The name is generated from the media id, never from anything the uploader supplied.
        assertThat(full.filename()).isEqualTo("media-1.jpg");
        assertThat(thumb.filename()).isEqualTo("media-1.jpg");
    }

    @Test
    void reportsAnUnknownIdAsNotFound() {
        when(mediaRepository.findPublicById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.load("nope", MediaVariant.FULL))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(storageService, never()).load(anyString());
    }

    @Test
    void reportsARowWhoseFileIsMissingAsNotFound_notAsAServerError() {
        // The documented cost of keeping bytes outside the database: restoring a database newer
        // than the media directory leaves rows pointing at files that are not there.
        when(mediaRepository.findPublicById("media-1")).thenReturn(Optional.of(storedMedia()));
        when(storageService.load(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.load("media-1", MediaVariant.FULL))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void intakeWritesBothFilesAndCreatesNoRow_theCallerDecidesWhatTheyBecome() {
        StoredImage image = mediaService.intake(upload(TestImages.jpeg(600, 300)));

        verify(storageService).store(org.mockito.ArgumentMatchers.eq(image.storageKey()), any(byte[].class));
        verify(storageService).store(org.mockito.ArgumentMatchers.eq(image.thumbnailStorageKey()), any(byte[].class));
        verifyNoInteractions(mediaRepository);
        assertThat(image.width()).isEqualTo(200);
        assertThat(image.height()).isEqualTo(100);
        assertThat(image.storageKey()).matches("[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f-]{36}\\.jpg");
        assertThat(image.thumbnailStorageKey()).endsWith("_thumb.jpg");
        assertThat(image.checksumSha256()).hasSize(64);
    }

    @Test
    void deleteAfterCommitDeletesTheRowNow_andTheFilesOnlyOnceTheTransactionHasCommitted() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            MediaEntity media = storedMedia();

            mediaService.deleteAfterCommit(media);

            verify(mediaRepository).delete(media);
            verify(storageService, never()).delete(anyString());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(storageService).delete("ab/cd/file.jpg");
            verify(storageService).delete("ab/cd/file_thumb.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteAfterCommitLeavesTheFilesAlone_whenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            mediaService.deleteAfterCommit(storedMedia());

            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(storageService, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * The hook runs after the commit, so a storage that throws must not turn a request that has committed into a
     * failure, and the first file that cannot be removed must not keep the second from being tried.
     */
    @Test
    void deleteAfterCommitSwallowsAStorageThatThrows_andStillTriesTheOtherFile() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            doThrow(new IllegalStateException("the object store is down")).when(storageService)
                    .delete("ab/cd/file.jpg");
            mediaService.deleteAfterCommit(storedMedia());

            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();

            verify(storageService).delete("ab/cd/file.jpg");
            verify(storageService).delete("ab/cd/file_thumb.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------- the new files of an attach: deleted only if no commit was tried
    //
    // Every case below runs against ScriptedTransactionManager, a real AbstractPlatformTransactionManager, so the status
    // each synchronization is told is Spring's own and not the test's idea of it. The rule and its reasons are the
    // javadoc of MediaService#attachInTransaction and docs/recipe-list-cover.md.

    private static final StoredImage NEW_FILES = new StoredImage("ab/cd/new.jpg", "ab/cd/new_thumb.jpg",
            ImageFormat.JPEG, 10, 10, 1, "sum");
    private static final String OWNER = "recipe list list-1";

    /** What the transaction manager told the synchronizations once the attach was over, for the case under test. */
    private final AtomicInteger reportedCompletion = new AtomicInteger(-1);

    /**
     * Called by the work itself, so after the hook of the service is registered: a probe that records the status and
     * decides nothing.
     */
    private void probeTheCompletion() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                reportedCompletion.set(status);
            }
        });
    }

    private void assertBothNewFilesWereDeleted() {
        verify(storageService).delete("ab/cd/new.jpg");
        verify(storageService).delete("ab/cd/new_thumb.jpg");
    }

    @Test
    void aWorkThatThrows_isACertainRollback_soBothNewFilesAreDeleted() {
        assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
            probeTheCompletion();
            throw new IllegalStateException("the list was deleted meanwhile");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("deleted meanwhile");

        assertThat(reportedCompletion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        assertBothNewFilesWereDeleted();
    }

    /**
     * <strong>The case this rule exists for.</strong> The work returned, so the commit was attempted, and it failed at the
     * database (the connection dropped as PostgreSQL committed: a {@code DataAccessException}). Spring says
     * « rolled back » (asserted below, so that the test cannot pass for being a different case), and the server may in fact
     * have committed: deleting the files would leave a row whose files are gone. They are kept.
     */
    @Test
    void aCommitThatFailsAfterTheWorkReturned_isReportedRolledBackBySpring_yetKeepsBothNewFiles() {
        transactionManager.failingToCommitWith(new DataAccessResourceFailureException("the connection dropped"));
        assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
            probeTheCompletion();
            return "attached";
        })).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(reportedCompletion.get()).as("what the transaction manager reports for a commit that failed at the database")
                .isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void aCommitWhoseOutcomeIsUnknown_keepsBothNewFiles() {
        transactionManager.failingToCommitWith(new TransactionSystemException("could not commit"));

        assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
            probeTheCompletion();
            return "attached";
        })).isInstanceOf(TransactionSystemException.class);

        assertThat(reportedCompletion.get()).isEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
        verify(storageService, never()).delete(anyString());
    }

    /** The work threw, but the rollback itself failed (the connection died mid-attach): nobody knows what the database holds. */
    @Test
    void aRollbackThatItselfFails_isUnknown_andKeepsBothNewFiles() {
        transactionManager.failingToRollbackWith(new TransactionSystemException("could not roll back"));

        assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
            probeTheCompletion();
            throw new IllegalStateException("the work failed");
        })).isInstanceOf(TransactionSystemException.class);

        assertThat(reportedCompletion.get()).isEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void aCommitThatCompleted_keepsBothNewFiles_andReturnsWhatTheWorkReturned() {
        String attached = mediaService.attachInTransaction(NEW_FILES, OWNER, () -> "attached");

        assertThat(attached).isEqualTo("attached");
        assertThat(transactionManager.commits()).isEqualTo(1);
        verify(storageService, never()).delete(anyString());
    }

    /** Nothing was written to the database, and the work never ran: the files have nothing to belong to. */
    @Test
    void aTransactionThatNeverBegins_deletesBothNewFiles_andNeverRunsTheWork() {
        transactionManager.failingToBeginWith(new CannotCreateTransactionException("no connection is available"));
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
            ran.set(true);
            return "attached";
        })).isInstanceOf(CannotCreateTransactionException.class);

        assertThat(ran).as("the work ran without a transaction").isFalse();
        assertBothNewFilesWereDeleted();
    }

    /**
     * Inside someone else's transaction the template would join it, and the hook would be told how <em>that</em> one
     * ended: refused, with the work not run and the files untouched (the caller's own transaction decides about them).
     */
    @Test
    void aCallerThatIsAlreadyInATransaction_isRefused_withTheWorkNotRunAndTheFilesUntouched() {
        AtomicBoolean ran = new AtomicBoolean();

        new TransactionTemplate(transactionManager).executeWithoutResult(outer ->
                assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
                    ran.set(true);
                    return "attached";
                })).isInstanceOf(IllegalStateException.class).hasMessageContaining(OWNER));

        assertThat(ran).as("the work ran inside the caller's transaction").isFalse();
        verify(storageService, never()).delete(anyString());
    }

    /** Deleting is best effort: one file the storage cannot delete does not keep the other from being deleted. */
    @Test
    void aStorageThatThrowsOnTheFirstFile_doesNotKeepTheSecondFromBeingDeleted() {
        doThrow(new IllegalStateException("the object store is down")).when(storageService).delete("ab/cd/new.jpg");

        assertThatThrownBy(() -> mediaService.attachInTransaction(NEW_FILES, OWNER, () -> {
            throw new IllegalStateException("the work failed");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("the work failed");

        verify(storageService).delete("ab/cd/new_thumb.jpg");
    }

    @Test
    void deleteAfterCommitWithNoTransactionFailsLoudly_andDeletesNothing() {
        assertThatThrownBy(() -> mediaService.deleteAfterCommit(storedMedia()))
                .isInstanceOf(IllegalStateException.class);

        verify(mediaRepository, never()).delete(any());
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void opensARowTheCallerHasAlreadyAuthorised_withoutLookingItUp() {
        when(storageService.load("ab/cd/file_thumb.jpg")).thenReturn(Optional.of(new ByteArrayResource(new byte[]{1})));

        StoredMedia opened = mediaService.open(storedMedia(), MediaVariant.THUMBNAIL);

        assertThat(opened.entityTag()).endsWith("-thumbnail\"");
        verifyNoInteractions(mediaRepository);
    }

    private MediaEntity storedMedia() {
        MediaEntity media = new MediaEntity();
        media.setId("media-1");
        media.setStorageKey("ab/cd/file.jpg");
        media.setThumbnailStorageKey("ab/cd/file_thumb.jpg");
        media.setContentType("image/jpeg");
        return media;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
