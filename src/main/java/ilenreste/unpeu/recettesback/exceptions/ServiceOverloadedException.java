package ilenreste.unpeu.recettesback.exceptions;

import java.time.Duration;

/**
 * The server is momentarily at capacity for this operation — mapped to
 * {@code 503 Service Unavailable} with a {@code Retry-After} header.
 * <p>
 * Deliberately not a 500: nothing failed and nothing is wrong with the request.
 * Image decoding is bounded to a few concurrent operations because it is the
 * memory-hungry step, and answering "try again shortly" is what turns a burst of
 * simultaneous uploads into a queue rather than an {@code OutOfMemoryError} that
 * takes the whole application down.
 */
public class ServiceOverloadedException extends RuntimeException {

    private final Duration retryAfter;

    public ServiceOverloadedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long the client should wait, rendered into {@code Retry-After}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
