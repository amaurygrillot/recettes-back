package ilenreste.unpeu.recettesback.exceptions;

/**
 * The request payload is semantically unusable in a way bean validation cannot
 * express — mapped to {@code 400 Bad Request}.
 * <p>
 * Covers what {@code @Valid} annotations cannot decide from the declared shape
 * alone: an upload whose leading bytes are not an allowed image format, an image
 * whose header declares more pixels than we will decode, an unknown enum-like
 * query parameter.
 * <p>
 * {@link InvalidReferenceException} is the specialised case for a bad id, and
 * extends this so one handler covers both.
 */
public class InvalidInputException extends RuntimeException {

    public InvalidInputException(String message) {
        super(message);
    }

    /**
     * Keeps the underlying failure attached when the 400 was decided by catching
     * something lower down — a decoder's {@code IIOException} on truncated upload
     * bytes, say. The cause never reaches the client (the advice sends only the
     * message), but it is what makes the logged warning actionable.
     */
    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
