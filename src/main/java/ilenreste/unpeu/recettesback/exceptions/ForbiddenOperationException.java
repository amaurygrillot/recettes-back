package ilenreste.unpeu.recettesback.exceptions;

/**
 * The caller is authenticated but not allowed to perform this operation —
 * mapped to {@code 403 Forbidden}.
 * <p>
 * Thrown for ownership rules that URL-level role matchers cannot express, such
 * as "you may edit this recipe because you wrote it". See
 * {@code docs/optional-authentication.md}.
 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
