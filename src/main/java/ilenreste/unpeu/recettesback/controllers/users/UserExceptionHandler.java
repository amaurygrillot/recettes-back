package ilenreste.unpeu.recettesback.controllers.users;

import ilenreste.unpeu.recettesback.exceptions.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

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
 * indistinguishable response — and that decision belongs next to the endpoint
 * whose security property depends on it.
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

    static final String RESET_FAILED_DETAIL = "Invalid password reset request.";

    /**
     * {@code UserController}'s other endpoints throw typed exceptions, so in
     * practice only the reset flow reaches this. It logs at {@code warn} with
     * the exception so that an unexpected {@link IllegalStateException} landing
     * here — and being reported as a 400 rather than a 500 — is still visible.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handlePasswordResetFailure(IllegalStateException exception,
                                                             HttpServletRequest request) {
        log.warn("Password reset refused", exception);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, RESET_FAILED_DETAIL);
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.badRequest().body(body);
    }
}
