package ilenreste.unpeu.recettesback.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates when it can, and declines to decide when it cannot.
 * <p>
 * The filter runs on <strong>every</strong> request — there is no
 * {@code shouldNotFilter}. Skipping it on public routes is what would make an
 * authenticated {@code GET /recipes} blind to its own caller, and would leave
 * {@code AuditorAware} unable to see the current user on any mixed-access
 * route. {@code SecurityFilterConfig} is the single source of truth for who may
 * reach what; this filter only establishes identity.
 *
 * <table>
 *   <caption>Behaviour by credential offered</caption>
 *   <tr><td>No {@code Authorization} header</td>
 *       <td>continue unauthenticated — the chain decides</td></tr>
 *   <tr><td>Header not starting with {@code Bearer }</td>
 *       <td>continue unauthenticated — no bearer credential was offered</td></tr>
 *   <tr><td>{@code Bearer <valid>}</td><td>authenticated</td></tr>
 *   <tr><td>{@code Bearer <invalid>}</td>
 *       <td>401, except on {@link #CREDENTIAL_ENDPOINTS}</td></tr>
 * </table>
 * <p>
 * Rows two and four are different cases and must not be collapsed:
 * {@code Authorization: Basic abc} offers no bearer credential at all, while
 * {@code Bearer <garbage>} is a caller who thinks they are authenticated and is
 * not. Silently downgrading the latter to anonymous turns an expired session
 * into a confusing 403 on the next write.
 *
 * @see ilenreste.unpeu.recettesback.configuration.SecurityFilterConfig
 */
@Log4j2
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Routes whose entire purpose is to <em>obtain</em> a working credential.
     * On these, a broken token is ignored rather than rejected.
     * <p>
     * Rejecting them for holding a broken one is a deadlock, and not a
     * hypothetical one: {@code RsaKeyConfig} generates the RSA key pair in
     * memory at every startup, so every restart invalidates every token in
     * circulation. An SPA whose HTTP interceptor attaches its stored token to
     * all requests would then be 401'd on {@code POST /auth/login} itself,
     * before the controller ever runs, and could never obtain a fresh token —
     * the only escape being to clear browser storage by hand.
     * {@code PUT /users/reinit-password} is worse still: a dead session is
     * precisely why someone is on the password-reset screen.
     * <p>
     * This list is <strong>stable</strong>. It does not grow when public routes
     * are added, because a public <em>read</em> route needs nothing from it.
     */
    private static final List<RequestMatcher> CREDENTIAL_ENDPOINTS = List.of(
            PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.POST, "/auth/**"),
            PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.POST, "/users/create"),
            PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.PUT, "/users/reinit-password")
    );

    private final JwtDecoder jwtDecoder;

    public JwtAuthenticationFilter(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Jwt jwt = jwtDecoder.decode(authHeader.substring(BEARER_PREFIX.length()));
            SecurityContextHolder.getContext().setAuthentication(toAuthentication(jwt));
        } catch (JwtException jwtException) {
            if (isCredentialEndpoint(request)) {
                // Let the request through unauthenticated so the caller can get a working token.
                log.debug("Ignoring an undecodable JWT on a credential endpoint", jwtException);
            } else {
                // Client-supplied input, and an expired token is a routine event: warn, not error.
                log.warn("Rejecting request with an undecodable JWT", jwtException);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isCredentialEndpoint(HttpServletRequest request) {
        return CREDENTIAL_ENDPOINTS.stream().anyMatch(matcher -> matcher.matches(request));
    }

    /**
     * A token minted without a {@code roles} claim yields no authorities rather
     * than a {@link NullPointerException}: the request then fails authorization
     * as a clean 403, which is what it is, instead of a 500.
     */
    private JwtAuthenticationToken toAuthentication(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        List<SimpleGrantedAuthority> authorities = roles == null ? List.of() : roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
