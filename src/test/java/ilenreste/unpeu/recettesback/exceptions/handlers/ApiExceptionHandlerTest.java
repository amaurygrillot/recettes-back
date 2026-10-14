package ilenreste.unpeu.recettesback.exceptions.handlers;

import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.exceptions.RateLimitExceededException;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.exceptions.ServiceOverloadedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private HttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void resourceNotFound_mapsTo404_withTheOffendingIdInTheDetail() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotFound(ResourceNotFoundException.of("recipe", "7b1c"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("No recipe with id 7b1c");
        // No assertion on getInstance(): the handlers no longer set it. Spring MVC's
        // HttpEntityMethodProcessor fills it from the request URI when writing the response, which
        // calling the handler directly never reaches. It is asserted over real HTTP instead, in
        // RecipesIntegrationTest.problemResponsesCarryTheRequestUriAsTheirInstance.
    }

    @Test
    void invalidReference_mapsTo400_andNamesTheFieldAndMissingIds() {
        InvalidReferenceException exception =
                new InvalidReferenceException("categoryIds", List.of("zzz", "aaa"));

        ResponseEntity<ProblemDetail> response = handler.handleInvalidInput(exception);

        // 400, not 404: /recipes exists and is reachable - it is the payload that is wrong.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        // Sorted, so the message is deterministic whatever order the ids arrived in.
        assertThat(response.getBody().getDetail()).isEqualTo("Unknown categoryIds: aaa, zzz");
    }

    @Test
    void resourceConflict_mapsTo409() {
        ResponseEntity<ProblemDetail> response =
                handler.handleConflict(new ResourceConflictException("Tag is still in use"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Tag is still in use");
    }

    @Test
    void forbiddenOperation_mapsTo403() {
        ResponseEntity<ProblemDetail> response = handler.handleForbidden(
                new ForbiddenOperationException("Only the author may edit this recipe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Only the author may edit this recipe");
    }

    @Test
    void rateLimitExceeded_mapsTo429_withRetryAfter() {
        // 429 and not 503: the request is fine, this caller has simply made too many of them.
        // Telling them the server is unwell would send them looking in the wrong place.
        ResponseEntity<ProblemDetail> response = handler.handleRateLimited(
                new RateLimitExceededException("Too many uploads.", Duration.ofMinutes(4)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Too many uploads.");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("240");
    }

    @Test
    void serviceOverloaded_mapsTo503_withRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleOverloaded(
                new ServiceOverloadedException("The server is busy.", Duration.ofSeconds(10)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
    }

    @Test
    void retryAfterNeverRendersAsZero_whichAClientWouldReadAsImmediately() {
        // The header is whole seconds, so a sub-second wait would floor to 0 and invite a hot retry
        // loop against the thing that just asked the caller to slow down.
        ResponseEntity<ProblemDetail> response = handler.handleRateLimited(
                new RateLimitExceededException("Too many uploads.", Duration.ofMillis(120)));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void uniqueViolation_mapsTo409_evenWhenTheSqlExceptionIsNestedSeveralLevelsDown() {
        // Mirrors the real shape: Spring wraps Hibernate, which wraps the driver's SQLException.
        // How deeply it nests is a version detail, which is why the handler walks the chain.
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("hibernate wrapper", new SQLException("duplicate key", "23505")));

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("That value already exists.");
    }

    @Test
    void nonUniqueIntegrityViolation_mapsTo500_notConflict() {
        // A NOT NULL breach means an application bug (a missing @NotBlank, a forgotten audit
        // listener). Reporting it as 409 would tell the caller to resolve a conflict that does not
        // exist AND skip the only branch that logs a stack trace, leaving the real bug invisible.
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "null value in column violates not-null constraint",
                new SQLException("not null violation", "23502"));

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(ApiExceptionHandler.GENERIC_500_DETAIL);
    }

    @Test
    void integrityViolationWithNoSqlExceptionAtAll_mapsTo500() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("no cause at all"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void selfReferencingCauseChain_terminates_ratherThanLoopingForever() {
        SQLException selfReferencing = new SQLException("odd driver", "08006") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("wrapped", selfReferencing));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void unexpectedException_mapsTo500_andLeaksNothingAboutItself() {
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(
                new IllegalArgumentException("connection to db-prod-01 at 10.0.0.7 refused"), request("/recipes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        // Every detail reaches a possibly-anonymous caller, so the real message stays in the log.
        assertThat(response.getBody().getDetail()).isEqualTo(ApiExceptionHandler.GENERIC_500_DETAIL);
        assertThat(response.getBody().getDetail()).doesNotContain("10.0.0.7");
    }
}
