package ilenreste.unpeu.recettesback.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.sql.SQLException;

/**
 * The single place that turns an exception type into an HTTP status, so that
 * controllers hold no {@code try/catch} at all.
 * <p>
 * Ordered {@link Ordered#LOWEST_PRECEDENCE} on purpose. Spring resolves an
 * exception by walking {@code @ControllerAdvice} beans in {@code @Order}
 * sequence and taking the <em>first</em> one with a matching handler method —
 * specificity of the exception type only breaks ties within a single class. A
 * targeted advice must therefore be {@link Ordered#HIGHEST_PRECEDENCE}, or it
 * becomes dead code that no status-only test would ever notice. See
 * {@code docs/api-error-handling.md}.
 * <p>
 * Extending {@link ResponseEntityExceptionHandler} means Spring's own
 * exceptions (bean-validation failures, unreadable bodies, unsupported methods)
 * come back in the same RFC 9457 {@code ProblemDetail} shape.
 */
@Log4j2
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Every {@code detail} reaches a caller who may be anonymous — every GET in
     * this API is public — so an unexpected failure says nothing about itself.
     * The stack trace goes to the log, never to the response.
     */
    static final String GENERIC_500_DETAIL = "The request could not be processed.";

    /** PostgreSQL SQLState for a unique-constraint violation. */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException exception,
                                                 HttpServletRequest request) {
        log.debug("Resource not found: {}", exception.getMessage());
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidReferenceException.class)
    ResponseEntity<ProblemDetail> handleInvalidReference(InvalidReferenceException exception,
                                                         HttpServletRequest request) {
        log.debug("Request body referenced something unknown: {}", exception.getMessage());
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(ResourceConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ResourceConflictException exception,
                                                 HttpServletRequest request) {
        log.info("Request conflicts with existing state: {}", exception.getMessage());
        return problem(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ResponseEntity<ProblemDetail> handleForbidden(ForbiddenOperationException exception,
                                                  HttpServletRequest request) {
        log.warn("Operation refused: {}", exception.getMessage());
        return problem(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    /**
     * Only a <em>unique</em> violation is a conflict.
     * <p>
     * {@link DataIntegrityViolationException} is Spring's wrapper for every
     * constraint class — NOT NULL, foreign key, check — and most of those mean
     * an application bug. Mapping the whole type to 409 would both tell the
     * caller to resolve a conflict that does not exist and hide the real bug,
     * since 409 is the branch that does not log a stack trace. The narrowing
     * tests the SQLState rather than the message text: constraint messages are
     * Postgres-version specific and locale-dependent.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException exception,
                                                               HttpServletRequest request) {
        if (isUniqueViolation(exception)) {
            log.info("A unique constraint lost a create race", exception);
            return problem(HttpStatus.CONFLICT, "That value already exists.", request);
        }
        log.error("Unexpected data integrity violation", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_500_DETAIL, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception while serving {} {}",
                request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_500_DETAIL, request);
    }

    /**
     * Walks the cause chain rather than inspecting only the immediate cause:
     * how deeply Hibernate and Spring nest the driver's {@link SQLException}
     * is an implementation detail of versions we do not control.
     */
    private boolean isUniqueViolation(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String detail,
                                                  HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).body(body);
    }
}
