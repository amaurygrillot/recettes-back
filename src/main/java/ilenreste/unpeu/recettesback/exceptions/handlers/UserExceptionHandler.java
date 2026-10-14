package ilenreste.unpeu.recettesback.exceptions.handlers;

import ilenreste.unpeu.recettesback.controllers.users.UserController;
import ilenreste.unpeu.recettesback.exceptions.PasswordResetFailedException;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/**
 * Keeps the password-reset endpoint's anti-enumeration guarantee intact through
 * the move to typed exceptions and {@link ApiExceptionHandler}.
 * <p>
 * Unknown email, unknown token, expired token and a token issued to another
 * account all answer with the same flat 400 and the same message. That is not
 * an oversight: distinguishing them would turn the endpoint into an oracle for
 * which addresses have accounts. See {@code docs/password-reset.md}.
 * <p>
 * This advice earns its place precisely because it does something the global
 * one must not — deliberately collapse four distinguishable failures into one
 * indistinguishable response.
 * <p>
 * It sits in {@code exceptions.handlers} next to {@link ApiExceptionHandler},
 * not in {@code controllers}. It is not a controller and maps no route: it is a
 * {@code @RestControllerAdvice}, one of the two halves of this application's
 * exception-to-status mapping, and the pair only makes sense read together —
 * the {@code @Order} on each is what decides which of them wins, and that is
 * impossible to notice when they live in different packages.
 * <p>
 * {@link Ordered#HIGHEST_PRECEDENCE} is load-bearing. Spring takes the first
 * advice in {@code @Order} sequence that has a matching handler method;
 * exception-type specificity does not make a targeted advice win over a global
 * one. Ordered the other way round this class is dead code, and a test that
 * only asserts the status would never notice.
 */
@Log4j2
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = UserController.class)
public class UserExceptionHandler {

    public static final String RESET_FAILED_DETAIL = "Invalid password reset request.";

    /**
     * Catches {@link PasswordResetFailedException} and nothing wider.
     * <p>
     * It used to catch {@code IllegalStateException}, which meant every
     * {@code IllegalStateException} raised anywhere under {@code UserController} —
     * a missing {@code USER} role row on registration, a JVM without SHA-256, an
     * ordinary bug — came back as {@code 400 Invalid password reset request}. That
     * is the wrong status on the wrong endpoint with a message pointing at the
     * wrong flow, and it hid genuine 500s behind a plausible-looking 400. The
     * narrow type is what keeps this advice deliberately blind <em>only</em> to the
     * four failures it is supposed to be blind to.
     * <p>
     * Still logged at {@code warn} with the exception, because the response cannot
     * say which of the four it was and the log is the only place that can.
     */
    @ExceptionHandler(PasswordResetFailedException.class)
    ResponseEntity<ProblemDetail> handlePasswordResetFailure(PasswordResetFailedException exception) {
        log.warn("Password reset refused", exception);
        // No setInstance, and so no HttpServletRequest parameter: Spring MVC fills a returned
        // ProblemDetail instance field from the request URI when it is null. See the note on
        // ApiExceptionHandler.problem.
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, RESET_FAILED_DETAIL));
    }
}
