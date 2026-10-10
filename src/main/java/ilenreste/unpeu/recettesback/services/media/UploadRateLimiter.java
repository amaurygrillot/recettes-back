package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.properties.MediaProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many uploads one account may make inside a rolling window.
 * <p>
 * Without this, one authenticated user can post 1000 files in a row and fill the
 * disk, and nothing in the pipeline objects: every gate in {@code MediaService}
 * is about <em>one</em> file (is it a JPEG, is it too many pixels, is the decoder
 * busy) and all of them pass a thousand times in a row. The storage root is a VPS
 * volume shared with the database, so a full disk is not "uploads stop working",
 * it is "Postgres stops accepting writes".
 *
 * <h2>Rolling window, not a fixed one</h2>
 * Timestamps are kept per user and the ones older than the window are dropped on
 * each check. A fixed window — a counter reset every ten minutes — lets a caller
 * send the whole allowance at 09:59:59 and the whole allowance again at 10:00:01,
 * which is twice the limit in two seconds, exactly the burst the limit exists to
 * stop. Keeping the timestamps costs at most {@code upload-limit} instants per
 * uploader, and an entry whose window has drained is dropped once the map grows
 * past {@link #EVICTION_THRESHOLD}.
 *
 * <h2>In memory, and what that means</h2>
 * The counters live in this instance's heap, like the RSA key pair in
 * {@code RsaKeyConfig}. Consequences, both accepted deliberately on a
 * single-instance deployment: a restart forgives everyone, and a second instance
 * would double the effective limit. The fix for both is the same shared store
 * (Redis, or a {@code media} count query) and neither is worth it before there is
 * a second instance — but a limit that is <em>sometimes</em> enforced is still
 * the difference between a bounded disk and an unbounded one.
 * <p>
 * It is also not a quota. It bounds the <em>rate</em>, so a determined uploader
 * can still fill a disk slowly; the second half of this is a per-user byte quota
 * summed from {@code media.size_bytes}, which needs an orphan sweep to run first
 * so that abandoned uploads stop counting against their uploader. Written down in
 * {@code docs/media-storage.md} rather than half-built here.
 */
@Log4j2
@Component
public class UploadRateLimiter {

    /**
     * Map size at which a refused-or-granted check also drops the users whose
     * windows have drained. Nothing else would: an entry is trimmed only when that
     * same user uploads again, so an account that uploads once and never returns
     * would hold a one-element deque until the next restart. A few thousand of
     * those are harmless, which is why this is a safety valve at a round number
     * rather than a scheduled job — there is no scheduler in this application and
     * adding one for a few kilobytes would be the wrong trade.
     */
    private static final int EVICTION_THRESHOLD = 1000;

    private final MediaProperties properties;
    private final Clock clock;

    /**
     * One entry per user with an upload inside the window, holding that user's
     * upload instants oldest-first. Mutated only inside
     * {@link ConcurrentHashMap#compute}, which is what makes the
     * read-trim-append-decide sequence atomic per key — two requests from the same
     * account on two Tomcat threads cannot both see "one slot left" and take it.
     */
    private final Map<String, Deque<Instant>> uploadsByUser = new ConcurrentHashMap<>();

    public UploadRateLimiter(MediaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Counts one upload against {@code userId}'s allowance.
     *
     * @throws RateLimitExceededException if the allowance is already spent, carrying the wait
     *                                   until the oldest counted upload leaves the window. Nothing
     *                                   is counted in that case, so a refused caller does not push
     *                                   their own window further out by retrying
     */
    public void requirePermit(String userId) {
        if (uploadsByUser.size() >= EVICTION_THRESHOLD) {
            evictDrainedWindows();
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.uploadLimitWindow());

        // Holds the single value the compute block needs to communicate back out: null when the
        // upload was counted, otherwise how long the caller has to wait.
        Duration[] retryAfter = new Duration[1];

        uploadsByUser.compute(userId, (key, previous) -> {
            Deque<Instant> recent = previous == null ? new ArrayDeque<>() : previous;
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(windowStart)) {
                recent.pollFirst();
            }
            if (recent.size() >= properties.uploadLimit()) {
                retryAfter[0] = Duration.between(now, recent.peekFirst().plus(properties.uploadLimitWindow()));
                return recent;
            }
            recent.addLast(now);
            return recent;
        });

        if (retryAfter[0] != null) {
            log.warn("Refusing an upload from user {}: {} uploads already in the last {}",
                    userId, properties.uploadLimit(), properties.uploadLimitWindow());
            throw new RateLimitExceededException(
                    "You have uploaded %d files in the last %d minutes, which is the limit. Try again in %d seconds."
                            .formatted(properties.uploadLimit(),
                                    properties.uploadLimitWindow().toMinutes(),
                                    Math.max(1, retryAfter[0].toSeconds())),
                    retryAfter[0]);
        }
        log.debug("Upload permitted for user {}", userId);
    }

    /**
     * How many accounts the limiter is currently holding timestamps for.
     * <p>
     * Package-private and here for {@code UploadRateLimiterTest}: eviction's only
     * observable effect is memory the test cannot otherwise see, and a test that
     * could only assert "it did not throw" would not be testing eviction at all.
     */
    int trackedUsers() {
        return uploadsByUser.size();
    }

    /**
     * Forgets the users whose windows have fully drained. See
     * {@link #EVICTION_THRESHOLD}.
     * <p>
     * Per-key {@code compute} again rather than a {@code values().removeIf}: the
     * deques are plain {@link ArrayDeque}s, safe only because every read and write
     * of one happens inside a {@code compute} on its own key. Reading one from the
     * values view while another thread appends to it is exactly the unsynchronised
     * access that makes it unsafe.
     */
    private void evictDrainedWindows() {
        Instant windowStart = clock.instant().minus(properties.uploadLimitWindow());
        int before = uploadsByUser.size();
        for (String userId : uploadsByUser.keySet()) {
            uploadsByUser.compute(userId, (key, timestamps) -> {
                if (timestamps == null) {
                    return null;
                }
                while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(windowStart)) {
                    timestamps.pollFirst();
                }
                return timestamps.isEmpty() ? null : timestamps;
            });
        }
        log.debug("Upload rate limiter holds {} users, down from {}", uploadsByUser.size(), before);
    }
}
