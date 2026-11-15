package ilenreste.unpeu.recettesback.exceptions;

import java.time.Duration;

/**
 * A refusal that comes with a wait: {@link RateLimitExceededException} (429) and {@link ServiceOverloadedException}
 * (503), both answered with a {@code Retry-After} header.
 * <p>
 * The wait is kept as a {@link Duration}, and everything that shows it to a person or a client goes through
 * {@link #retryAfterSeconds()}, so the header and the sentence in {@code detail} can never disagree.
 */
public abstract class RetryableException extends RuntimeException {

    private final Duration retryAfter;

    protected RetryableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long the caller should wait before trying again. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** {@link #retryAfter()} as the whole seconds of the {@code Retry-After} header. */
    public long retryAfterSeconds() {
        return wholeSeconds(retryAfter);
    }

    /**
     * A wait as whole seconds: <strong>rounded up</strong>, and never below one.
     * <p>
     * The header carries whole seconds, so a wait of 59.4 s has to be sent as 60: truncated to 59, a client that
     * obeys the header comes back 0.4 s early and is refused a second time. A sub-second wait would truncate to 0,
     * which a client reads as "immediately" and turns into a hot retry loop against the thing that just asked it
     * to slow down, so the floor is one.
     */
    public static long wholeSeconds(Duration wait) {
        long seconds = wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
