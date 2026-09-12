package ilenreste.unpeu.recettesback.exceptions;

/**
 * A password reset that cannot be honoured: unknown email, unknown token,
 * expired token, or a token issued to a different account.
 * <p>
 * One type for all four, because {@link UserExceptionHandler} answers all four
 * identically and the distinction must not survive as far as the response — see
 * {@code docs/password-reset.md}. Being able to <em>throw</em> them apart would
 * be the first step towards accidentally reporting them apart.
 *
 * <h2>Why this exists instead of {@code IllegalStateException}</h2>
 * The reset flow used to throw a bare {@code IllegalStateException}, and the
 * advice caught that type across the whole of {@code UserController}. So
 * <em>any</em> {@code IllegalStateException} raised anywhere under that
 * controller — a missing {@code USER} role row on registration, a JVM without
 * SHA-256, a bug in code that has nothing to do with passwords — came back as
 * {@code 400 Invalid password reset request}: the wrong status, on the wrong
 * endpoint, with a message that sends whoever is debugging it to the wrong flow
 * entirely. A dedicated type means the advice catches what it actually means to
 * catch, and everything else keeps the 500 it deserves.
 */
public class PasswordResetFailedException extends RuntimeException {

    public PasswordResetFailedException(String message) {
        super(message);
    }
}
