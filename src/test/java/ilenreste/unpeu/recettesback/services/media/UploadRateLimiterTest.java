package ilenreste.unpeu.recettesback.services.media;

import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.properties.MediaProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int LIMIT = 3;

    /** A clock the test moves by hand — the only way to assert a window without waiting one out. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advanceBy(Duration amount) {
            now = now.plus(amount);
        }
    }

    private final MovableClock clock = new MovableClock();
    private final UploadRateLimiter limiter = new UploadRateLimiter(properties(), clock);

    private MediaProperties properties() {
        return new MediaProperties(Path.of("target", "test-media"), 8000, 40_000_000L,
                2000, 640, 0.85f, 4, Duration.ofSeconds(10), LIMIT, WINDOW);
    }

    @Test
    void allowsUpToTheLimitAndThenRefuses() {
        for (int upload = 0; upload < LIMIT; upload++) {
            limiter.requirePermit("user-1");
        }

        assertThatThrownBy(() -> limiter.requirePermit("user-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void countsEachAccountSeparately() {
        // The limit protects the disk from one user, and must not let one user's burst lock out
        // everybody else - which is what a single global counter would do.
        for (int upload = 0; upload < LIMIT; upload++) {
            limiter.requirePermit("noisy");
        }

        limiter.requirePermit("quiet");
    }

    @Test
    void forgetsAnUploadOnceItLeavesTheWindow() {
        for (int upload = 0; upload < LIMIT; upload++) {
            limiter.requirePermit("user-1");
        }
        clock.advanceBy(WINDOW.plusSeconds(1));

        limiter.requirePermit("user-1");
    }

    @Test
    void slidesRatherThanResetting() {
        // The reason timestamps are kept instead of a counter: with a fixed window, spending the
        // whole allowance just before a reset and the whole allowance just after is twice the limit
        // in two seconds. Here the third upload is still inside the window, so the fourth waits.
        limiter.requirePermit("user-1");
        clock.advanceBy(WINDOW.minusMinutes(1));
        limiter.requirePermit("user-1");
        limiter.requirePermit("user-1");

        clock.advanceBy(Duration.ofMinutes(2));     // only the first upload has aged out

        limiter.requirePermit("user-1");
        assertThatThrownBy(() -> limiter.requirePermit("user-1"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void reportsHowLongUntilTheOldestUploadLeavesTheWindow() {
        limiter.requirePermit("user-1");
        clock.advanceBy(Duration.ofMinutes(4));
        limiter.requirePermit("user-1");
        limiter.requirePermit("user-1");

        assertThatThrownBy(() -> limiter.requirePermit("user-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(thrown -> ((RateLimitExceededException) thrown).retryAfter())
                // The first upload was four minutes ago, so six of the ten remain on it.
                .isEqualTo(Duration.ofMinutes(6));
    }

    @Test
    void doesNotCountARefusedUpload_soRetryingDoesNotPushTheWindowOut() {
        for (int upload = 0; upload < LIMIT; upload++) {
            limiter.requirePermit("user-1");
        }
        clock.advanceBy(Duration.ofMinutes(9));
        assertThatThrownBy(() -> limiter.requirePermit("user-1"))
                .isInstanceOf(RateLimitExceededException.class);

        // A minute later the three originals have aged out. Had the refusal been counted, this
        // caller would be locked out for another nine minutes by their own retry.
        clock.advanceBy(Duration.ofMinutes(1).plusSeconds(1));
        limiter.requirePermit("user-1");
    }

    @Test
    void dropsAccountsWhoseWindowsHaveDrained_onceTheMapGrowsPastTheThreshold() {
        // An entry is trimmed only when that same account uploads again, so one-off uploaders would
        // otherwise accumulate until the next restart. The sweep is opportunistic rather than
        // scheduled: there is no scheduler in this application, and this costs a map walk on one
        // request in a thousand.
        for (int user = 0; user < 1000; user++) {
            limiter.requirePermit("one-off-" + user);
        }
        assertThat(limiter.trackedUsers()).isEqualTo(1000);

        clock.advanceBy(WINDOW.plusSeconds(1));
        limiter.requirePermit("someone-new");

        // Only the account that just uploaded is still tracked.
        assertThat(limiter.trackedUsers()).isEqualTo(1);
    }

    @Test
    void keepsAccountsStillInsideTheirWindowWhenItSweeps() {
        for (int user = 0; user < 999; user++) {
            limiter.requirePermit("one-off-" + user);
        }
        clock.advanceBy(WINDOW.plusSeconds(1));
        limiter.requirePermit("recent");
        clock.advanceBy(Duration.ofMinutes(1));

        // The 999 have drained and "recent" has not, so the sweep must keep exactly one of them -
        // evicting a live window would hand that account a fresh allowance.
        for (int user = 0; user < 1000; user++) {
            limiter.requirePermit("filler-" + user);
        }

        limiter.requirePermit("recent");
        limiter.requirePermit("recent");
        assertThatThrownBy(() -> limiter.requirePermit("recent"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void admitsExactlyTheLimitWhenTheSameAccountUploadsConcurrently() throws Exception {
        // Two Tomcat threads for one account must not both see "one slot left" and take it. The
        // check is a read-trim-append sequence, which is only atomic because it happens inside a
        // ConcurrentHashMap.compute on the user's own key.
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger();

        List<Thread> workers = java.util.stream.IntStream.range(0, threads)
                .mapToObj(worker -> new Thread(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        limiter.requirePermit("user-1");
                        admitted.incrementAndGet();
                    } catch (RateLimitExceededException expected) {
                        // One of the many that must be refused.
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                }))
                .toList();
        workers.forEach(Thread::start);
        start.countDown();

        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(admitted.get()).isEqualTo(LIMIT);
    }
}
