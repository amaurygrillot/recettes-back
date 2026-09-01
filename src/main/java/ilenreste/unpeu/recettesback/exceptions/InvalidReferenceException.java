package ilenreste.unpeu.recettesback.exceptions;

import java.util.Collection;
import java.util.TreeSet;

/**
 * An id <em>inside the request body</em> references something that does not
 * exist — mapped to {@code 400 Bad Request}.
 * <p>
 * The endpoint itself is perfectly reachable, so a 404 would send anyone
 * debugging the call in entirely the wrong direction. The message names the
 * offending field and the missing ids, which is what makes the 400 actionable
 * rather than just "bad request". See {@code docs/api-error-handling.md}.
 * <p>
 * Extends {@link InvalidInputException} — the general "this payload is not
 * usable" 400 — so one handler covers both and this type exists only to carry
 * the field-and-ids message.
 */
public class InvalidReferenceException extends InvalidInputException {

    public InvalidReferenceException(String message) {
        super(message);
    }

    /**
     * @param field      the request field carrying the bad ids, e.g. {@code "categoryIds"}
     * @param missingIds the ids that matched no row; sorted so the message is deterministic
     */
    public InvalidReferenceException(String field, Collection<String> missingIds) {
        super("Unknown %s: %s".formatted(field, String.join(", ", new TreeSet<>(missingIds))));
    }
}
