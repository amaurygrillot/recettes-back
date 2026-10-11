package ilenreste.unpeu.recettesback.exceptions.handlers;

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
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Answers security-layer rejections in the same RFC 9457 {@code problem+json}
 * shape as every application error, so a client never has to special-case 401
 * and 403 as the two responses with no body.
 * <p>
 * It lives in {@code exceptions.handlers} next to {@link ApiExceptionHandler}
 * rather than in {@code configuration}: it configures nothing — it is the second
 * half of "turn a failure into a {@code problem+json} body", the half that runs
 * above the {@code DispatcherServlet} where an {@code @ExceptionHandler} cannot
 * reach. {@code SecurityFilterConfig} is the configuration that wires it into the
 * chain.
 * <p>
 * It is a {@code @Component} rather than something {@code SecurityFilterConfig}
 * constructs inline, because {@link ilenreste.unpeu.recettesback.filters.JwtAuthenticationFilter}
 * needs it too. That filter sits <em>before</em> {@code ExceptionTranslationFilter}
 * in the chain, so an {@code AuthenticationException} it threw would never be
 * caught and turned into a response — it has to write one itself, through this
 * class, or answer with a bare status and no body.
 *
 * <h2>Who calls {@link #commence} and {@link #handle}, and when</h2>
 * Neither is ever called by application code. Both are callbacks invoked by
 * Spring Security's {@code ExceptionTranslationFilter}, which sits in the filter
 * chain and catches the two exception types that propagate <em>up</em> out of
 * everything below it — the rest of the chain, the servlet, and every controller.
 * When it catches one it asks "does this caller have a real identity yet?" and
 * picks accordingly:
 * <ul>
 *   <li>{@link #commence} — on an {@code AuthenticationException}, or on an
 *       {@code AccessDeniedException} raised for an <strong>anonymous</strong>
 *       caller. This is the {@link AuthenticationEntryPoint} contract: "nobody is
 *       signed in, start authentication". In this API it fires when an anonymous
 *       request reaches a route guarded by {@code authenticated()} or
 *       {@code hasRole(...)}, when {@link ilenreste.unpeu.recettesback.filters.JwtAuthenticationFilter}
 *       rejects a present-but-broken {@code Bearer} token, and when
 *       {@code POST /auth/login} is given a wrong password — that last one only
 *       because {@link ApiExceptionHandler} deliberately rethrows
 *       {@code AuthenticationException} instead of answering it, since a handler
 *       running inside the dispatcher is <em>below</em> this filter. Answers 401.</li>
 *   <li>{@link #handle} — on an {@code AccessDeniedException} raised for a caller
 *       who <strong>is</strong> authenticated: a valid token, but the wrong role.
 *       A {@code USER} calling {@code POST /categories} is the everyday case.
 *       Answers 403.</li>
 * </ul>
 * One class implements both because they differ only in the status they write.
 * Note that {@code ForbiddenOperationException} — "you are not the author of this
 * recipe" — does <em>not</em> come through here: it is an application exception
 * thrown inside a service and mapped to 403 by {@link ApiExceptionHandler}.
 * <p>
 * The detail messages are fixed strings. These responses reach unauthenticated
 * callers by definition, so they say nothing about what would have been
 * required or whether the target exists.
 */
@Log4j2
@Component
public class ProblemDetailErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String UNAUTHORIZED_DETAIL = "Authentication is required to access this resource.";
    public static final String FORBIDDEN_DETAIL = "You are not allowed to perform this operation.";

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
