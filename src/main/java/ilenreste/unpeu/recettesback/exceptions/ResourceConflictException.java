package ilenreste.unpeu.recettesback.exceptions;

/**
 * The request conflicts with existing state — mapped to {@code 409 Conflict}.
 * <p>
 * Covers a duplicate name on create and a delete refused because rows still
 * reference the target. Never used for malformed input, which is a 400.
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
