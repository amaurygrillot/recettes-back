package ilenreste.unpeu.recettesback.exceptions;

import java.time.Duration;

/**
 * This caller has made too many of these requests — mapped to
 * {@code 429 Too Many Requests} with a {@code Retry-After} header.
 * <p>
 * Distinct from {@link ServiceOverloadedException}, and the difference is who is
 * at fault. A 503 says "the <em>server</em> is momentarily busy, nothing is wrong
 * with your request"; a 429 says "the request is fine but <em>you</em> have made
 * too many of them". Collapsing the two would tell a client hammering the upload
 * endpoint that the server is unwell, and tell a client caught behind a genuine
 * decode burst that it is being throttled — each sending the reader to the wrong
 * place.
 * <p>
 * Neither is a 500 and neither is a 400: nothing failed and the payload is
 * valid.
 */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long until the caller's oldest counted request leaves the window. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
