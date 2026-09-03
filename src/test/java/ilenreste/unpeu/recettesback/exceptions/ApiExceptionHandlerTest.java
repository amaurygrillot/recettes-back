package ilenreste.unpeu.recettesback.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.sql.SQLException;
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
                handler.handleNotFound(ResourceNotFoundException.of("recipe", "7b1c"), request("/recipes/7b1c"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("No recipe with id 7b1c");
        assertThat(response.getBody().getInstance()).isEqualTo(URI.create("/recipes/7b1c"));
    }

    @Test
    void invalidReference_mapsTo400_andNamesTheFieldAndMissingIds() {
        InvalidReferenceException exception =
                new InvalidReferenceException("categoryIds", List.of("zzz", "aaa"));

        ResponseEntity<ProblemDetail> response = handler.handleInvalidInput(exception, request("/recipes"));

        // 400, not 404: /recipes exists and is reachable - it is the payload that is wrong.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        // Sorted, so the message is deterministic whatever order the ids arrived in.
        assertThat(response.getBody().getDetail()).isEqualTo("Unknown categoryIds: aaa, zzz");
    }

    @Test
    void resourceConflict_mapsTo409() {
        ResponseEntity<ProblemDetail> response =
                handler.handleConflict(new ResourceConflictException("Tag is still in use"), request("/tags/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Tag is still in use");
    }

    @Test
    void forbiddenOperation_mapsTo403() {
        ResponseEntity<ProblemDetail> response = handler.handleForbidden(
                new ForbiddenOperationException("Only the author may edit this recipe"), request("/recipes/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Only the author may edit this recipe");
    }

    @Test
    void uniqueViolation_mapsTo409_evenWhenTheSqlExceptionIsNestedSeveralLevelsDown() {
        // Mirrors the real shape: Spring wraps Hibernate, which wraps the driver's SQLException.
        // How deeply it nests is a version detail, which is why the handler walks the chain.
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("hibernate wrapper", new SQLException("duplicate key", "23505")));

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(exception, request("/tags"));

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

        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(exception, request("/media"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(ApiExceptionHandler.GENERIC_500_DETAIL);
    }

    @Test
    void integrityViolationWithNoSqlExceptionAtAll_mapsTo500() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("no cause at all"), request("/media"));

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
                new DataIntegrityViolationException("wrapped", selfReferencing), request("/media"));

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
