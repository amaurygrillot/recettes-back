package ilenreste.unpeu.recettesback.filters;

import ilenreste.unpeu.recettesback.models.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class AuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        AuthenticatedUser authenticatedUser = validateToken(token);
        if (authenticatedUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        var authorities = authenticatedUser.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        var authentication = new UsernamePasswordAuthenticationToken(
                authenticatedUser.username(),
                null,
                authorities
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private AuthenticatedUser validateToken(String token) {
        // Replace with JWT validation (signature, exp, etc.)
        return switch (token) {
            case "admin-token" ->
                    new AuthenticatedUser("admin", List.of("ADMIN", "USER"));
            case "user-token" ->
                    new AuthenticatedUser("user", List.of("USER"));
            default -> null;
        };
    }
}
