package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * What the row lock on a list is for: serialising every write to its photo, and the deletion of the list. The lock is
 * {@code PESSIMISTIC_WRITE} on the list's row, taken by {@code RecipeListService.ownedListForUpdate}.
 * <p>
 * <strong>These tests prove the lock, which a pair of requests released together cannot.</strong> Two requests fired at
 * once only overlap if the scheduler lets them, so without any lock they pass whenever the writes happen to run one after
 * the other. Here the overlap is made, not hoped for: the first write is <em>parked</em> inside its locked transaction
 * (at a step of its own that runs under the lock, a spy of the shared context), the second is started and shown to be
 * <em>waiting on the row lock</em> in {@code pg_stat_activity}, and only then is the first released. With the lock taken
 * out, the second write sails through while the first is parked, the wait is never seen and every test here fails, which is
 * how it was checked.
 * <p>
 * Every wait has a deadline, so a failure fails the test instead of hanging the build. It extends the support class of the
 * other list suites, so it runs in the same application context and connection pool (docs/testing-database.md).
 */
class RecipeListCoverConcurrencyIntegrationTest extends RecipeListsHttpSupport {

    /** The longest anything here waits; the real waits are milliseconds. */
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private static final byte[] LANDSCAPE = TestImages.jpeg(300, 200);
    private static final byte[] PORTRAIT = TestImages.jpeg(200, 300);
    private static final byte[] SMALL = TestImages.jpeg(240, 160);

    private final ExecutorService requests = Executors.newCachedThreadPool();
    private final List<Parked> parkedWrites = new ArrayList<>();

    /** Runs before the superclass teardown: nothing may be left holding a row lock when the lists are deleted. */
    @AfterEach
    void releaseWhateverIsStillParked() {
        parkedWrites.forEach(Parked::release);
        requests.shutdownNow();
        reset(mediaService);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /**
     * The first request to reach the spied step is held there, inside its transaction and so holding the list's row lock,
     * until {@link #release()}. Any other request passes straight through, which is what lets the second write run once the
     * first has gone.
     */
    private final class Parked {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean taken = new AtomicBoolean();

        Answer<Object> park() {
            return call -> {
                if (taken.compareAndSet(false, true)) {
                    reached.countDown();
                    assertThat(released.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                            .as("the test never released the parked write").isTrue();
                }
                return call.callRealMethod();
            };
        }

        void awaitReached() throws InterruptedException {
            assertThat(reached.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the first write never reached the step where it is to be parked").isTrue();
        }

        void release() {
            released.countDown();
        }
    }

    /**
     * Parks the first replace of a photo, at the step where it inserts the new row: after the lock, before anything is
     * written to the list.
     */
    private Parked parkTheFirstReplaceOfAPhoto() {
        Parked parked = new Parked();
        parkedWrites.add(parked);
        doAnswer(parked.park()).when(mediaService).persist(any());
        return parked;
    }

    /**
     * Parks the first removal of a photo, or the first deletion of a list that has one, at the step where the old row is
     * deleted: after the lock, before anything is committed.
     */
    private Parked parkTheFirstDeletionOfAPhoto() {
        Parked parked = new Parked();
        parkedWrites.add(parked);
        doAnswer(parked.park()).when(mediaService).deleteAfterCommit(any());
        return parked;
    }

    private <T> Future<T> start(Callable<T> request) {
        return requests.submit(request);
    }

    /**
     * Waits until PostgreSQL itself reports a backend waiting on a lock of {@code recipe_lists}: the second write is
     * blocked behind the first, which is the one thing a test without the lock cannot show. Gives up, with a message that
     * says what it means, if the second write finishes instead (nothing made it wait) or the deadline passes.
     */
    private void awaitBlockedOnTheRowLock(Future<?> second) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (second.isDone()) {
                throw new AssertionError("The second write finished while the first held the list's row lock: nothing "
                        + "made it wait, so the lock is not doing its job");
            }
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query ILIKE '%recipe_lists%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The second write was never seen waiting on the row lock within " + PATIENCE);
    }

    private static <T> T result(Future<T> request) throws InterruptedException, ExecutionException, TimeoutException {
        return request.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Set<String> keysOf(Map<String, Object> mediaRow) {
        return Set.of((String) mediaRow.get("storage_key"), (String) mediaRow.get("thumbnail_storage_key"));
    }

    private static Set<String> without(Set<String> files, Set<String> removed) {
        Set<String> remaining = new HashSet<>(files);
        remaining.removeAll(removed);
        return remaining;
    }

    private static Set<String> with(Set<String> files, Set<String> added) {
        Set<String> all = new HashSet<>(files);
        all.addAll(added);
        return all;
    }

    /** The width and the height of the image the server serves, which tells which of the uploads it is. */
    private int[] dimensionsOf(byte[] image) {
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(image));
            return new int[]{decoded.getWidth(), decoded.getHeight()};
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void assertServedPhotoIs(String listId, String version, int width, int height) {
        var served = getBytes(coverPath(listId) + "?v=" + version, ownerToken);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dimensionsOf(served.getBody())).as("the photo the list serves").containsExactly(width, height);
    }

    // ----------------------------------------------------------------------------------------------- two PUTs

    @Test
    void twoPutsOnTheSamePhoto_theSecondWaitsForTheFirst_andTheLastToCommitIsTheOnlyPhotoLeft() throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> original = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        Parked parked = parkTheFirstReplaceOfAPhoto();

        Future<ResponseEntity<String>> first = start(() -> putCover(id, ownerToken, PORTRAIT));
        parked.awaitReached();
        Future<ResponseEntity<String>> second = start(() -> putCover(id, ownerToken, SMALL));
        awaitBlockedOnTheRowLock(second);
        parked.release();

        ResponseEntity<String> firstAnswer = result(first);
        ResponseEntity<String> secondAnswer = result(second);
        assertThat(firstAnswer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondAnswer.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstVersion = json(firstAnswer).path("coverVersion").asString();
        String secondVersion = json(secondAnswer).path("coverVersion").asString();
        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(versionOf(id)).as("the last to commit wins").isEqualTo(secondVersion);
        assertThat(mediaRow(firstVersion)).as("the first's photo was replaced by the second, row and files").isEmpty();
        assertThat(mediaRow(original.get("id").toString())).as("and so was the original").isEmpty();
        assertThat(mediaRowsUploadedBy(owner.id())).as("exactly one photo row").isEqualTo(1);
        assertThat(storedFiles()).as("exactly the survivor's two files, in place of the original's")
                .isEqualTo(with(without(before, keysOf(original)), keysOf(mediaRow(secondVersion).orElseThrow())));
        assertServedPhotoIs(id, secondVersion, 240, 160);
        assertThat(getBytes(coverPath(id) + "?v=" + firstVersion, ownerToken).getStatusCode())
                .as("the replaced version is never served").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------------------------- a PUT and a removal

    @Test
    void aRemovalWaitingForAPut_removesThePhotoThePutAttached_andNothingIsLeft() throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> original = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        Parked parked = parkTheFirstReplaceOfAPhoto();

        Future<ResponseEntity<String>> put = start(() -> putCover(id, ownerToken, PORTRAIT));
        parked.awaitReached();
        Future<ResponseEntity<String>> removal = start(() -> delete(coverPath(id), ownerToken));
        awaitBlockedOnTheRowLock(removal);
        parked.release();

        ResponseEntity<String> putAnswer = result(put);
        ResponseEntity<String> removalAnswer = result(removal);
        assertThat(putAnswer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removalAnswer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(removalAnswer).path("coverVersion").isNull())
                .as("the removal came second and took away the photo the put had just attached").isTrue();
        assertThat(versionOf(id)).isNull();
        assertThat(mediaRowsUploadedBy(owner.id())).as("no photo row").isZero();
        assertThat(storedFiles()).as("neither the original's files nor the put's").isEqualTo(without(before, keysOf(original)));
        assertThat(getBytes(coverPath(id), ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPutWaitingForARemoval_attachesItsPhotoAfterIt_andItIsTheOnlyOneLeft() throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> original = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        Parked parked = parkTheFirstDeletionOfAPhoto();

        Future<ResponseEntity<String>> removal = start(() -> delete(coverPath(id), ownerToken));
        parked.awaitReached();
        Future<ResponseEntity<String>> put = start(() -> putCover(id, ownerToken, PORTRAIT));
        awaitBlockedOnTheRowLock(put);
        parked.release();

        ResponseEntity<String> removalAnswer = result(removal);
        ResponseEntity<String> putAnswer = result(put);
        assertThat(removalAnswer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putAnswer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(removalAnswer).path("coverVersion").isNull()).isTrue();
        String version = json(putAnswer).path("coverVersion").asString();
        assertThat(versionOf(id)).as("the put came second, so there is a photo").isEqualTo(version);
        assertThat(mediaRowsUploadedBy(owner.id())).isEqualTo(1);
        assertThat(storedFiles()).isEqualTo(with(without(before, keysOf(original)),
                keysOf(mediaRow(version).orElseThrow())));
        assertServedPhotoIs(id, version, 200, 300);
    }

    // ----------------------------------------------------------------------- a PUT and the deletion of the list

    @Test
    void aDeletionOfTheListWaitingForAPut_deletesThePhotoThePutAttached_andNothingIsLeft() throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> original = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        Parked parked = parkTheFirstReplaceOfAPhoto();

        Future<ResponseEntity<String>> put = start(() -> putCover(id, ownerToken, PORTRAIT));
        parked.awaitReached();
        Future<ResponseEntity<String>> deletion = start(() -> delete("/lists/" + id, ownerToken));
        awaitBlockedOnTheRowLock(deletion);
        parked.release();

        assertThat(result(put).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result(deletion).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/lists/" + id, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).as("the deletion saw the photo the put attached and took it too")
                .isZero();
        assertThat(storedFiles()).isEqualTo(without(before, keysOf(original)));
    }

    @Test
    void aPutWaitingForTheDeletionOfTheList_is404_andItsNewFilesAreRemoved() throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> original = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        Parked parked = parkTheFirstDeletionOfAPhoto();

        Future<ResponseEntity<String>> deletion = start(() -> delete("/lists/" + id, ownerToken));
        parked.awaitReached();
        Future<ResponseEntity<String>> put = start(() -> putCover(id, ownerToken, PORTRAIT));
        awaitBlockedOnTheRowLock(put);
        parked.release();

        assertThat(result(deletion).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> putAnswer = result(put);
        assertThat(putAnswer.getStatusCode()).as("the list was gone when the put got the lock: %s", putAnswer.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
        assertThat(storedFiles()).as("the original's files went with the list, and the put's new files were removed")
                .isEqualTo(without(before, keysOf(original)));
    }

    // ------------------------------------------------------------------------------------- two deletions of a list

    @Test
    void twoDeletionsOfTheSameList_theSecondWaitsForTheFirst_andAnswers404() throws Exception {
        String id = listWithCover(ownerToken);
        Map<String, Object> original = mediaRow(versionOf(id)).orElseThrow();
        Set<String> before = storedFiles();
        Parked parked = parkTheFirstDeletionOfAPhoto();

        Future<ResponseEntity<String>> first = start(() -> delete("/lists/" + id, ownerToken));
        parked.awaitReached();
        Future<ResponseEntity<String>> second = start(() -> delete("/lists/" + id, ownerToken));
        awaitBlockedOnTheRowLock(second);
        parked.release();

        assertThat(result(first).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(result(second).getStatusCode()).as("the list was gone when the second got the lock")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mediaRowsUploadedBy(owner.id())).isZero();
        assertThat(storedFiles()).isEqualTo(without(before, keysOf(original)));
    }
}
