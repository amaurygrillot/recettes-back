package ilenreste.unpeu.recettesback.configuration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Answers security-layer rejections in the same RFC 9457 {@code problem+json}
 * shape as every application error, so a client never has to special-case 401
 * and 403 as the two responses with no body.
 * <p>
 * One class serves as both {@link AuthenticationEntryPoint} (anonymous caller
 * to a protected route, 401) and {@link AccessDeniedHandler} (authenticated but
 * not permitted, 403), because the two differ only in the status they write.
 * <p>
 * The detail messages are fixed strings. These responses reach unauthenticated
 * callers by definition, so they say nothing about what would have been
 * required or whether the target exists.
 */
@Log4j2
public class ProblemDetailErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String UNAUTHORIZED_DETAIL = "Authentication is required to access this resource.";
    static final String FORBIDDEN_DETAIL = "You are not allowed to perform this operation.";

    private final ObjectMapper objectMapper;

    public ProblemDetailErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authenticationException) throws IOException {
        log.debug("Unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());
        write(request, response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_DETAIL);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Access denied for {} {}", request.getMethod(), request.getRequestURI());
        write(request, response, HttpStatus.FORBIDDEN, FORBIDDEN_DETAIL);
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String detail) throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
