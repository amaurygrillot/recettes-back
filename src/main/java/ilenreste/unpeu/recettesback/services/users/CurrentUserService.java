package ilenreste.unpeu.recettesback.services.users;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The single answer to "who is calling, and are they an admin?".
 * <p>
 * One place rather than one per caller, because the guard below is easy to get
 * subtly wrong and a second copy will eventually get it wrong differently.
 * <p>
 * <strong>The guard is a type check, not a null check.</strong> Anonymous
 * requests do not stop at the JWT filter; they continue down the chain, and
 * Spring Security's {@code AnonymousAuthenticationFilter} puts an
 * {@code AnonymousAuthenticationToken} in the context whose principal is the
 * <em>String</em> {@code "anonymousUser"}. So "no authentication" is not
 * {@code authentication == null}, and the obvious version — null check then cast
 * — throws {@link ClassCastException} on an anonymous request to a public route.
 * Checking {@code isAuthenticated()} would not catch it either: that token
 * reports {@code true}.
 */
@Service
public class CurrentUserService {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /** Empty for anonymous callers, and for anything running without a security context. */
    public Optional<String> currentUserId() {
        return currentToken().map(token -> (String) token.getToken().getClaims().get("userId"));
    }

    /**
     * Read from the token's authorities rather than from the database: the roles
     * claim is what the filter already turned into authorities, and re-reading
     * the user costs a query on every ownership check.
     */
    public boolean isAdmin() {
        return currentToken()
                .map(token -> token.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(ADMIN_AUTHORITY::equals))
                .orElse(false);
    }

    private Optional<JwtAuthenticationToken> currentToken() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast);
    }
}
