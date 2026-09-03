package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.filters.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The single source of truth for who may reach what.
 * <p>
 * {@link JwtAuthenticationFilter} establishes identity and nothing else, so
 * every public route is declared here once instead of in two hand-maintained
 * lists that have to be kept in sync.
 */
@Configuration
public class SecurityFilterConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                            ObjectMapper objectMapper) throws Exception {

        ProblemDetailErrorResponder errorResponder = new ProblemDetailErrorResponder(objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Without an explicit entry point, Spring Security falls back to
                // Http403ForbiddenEntryPoint and an anonymous request to a protected route answers
                // 403 where it must answer 401. That regression would be silent and permanent: the
                // filter no longer rejects anonymous callers itself, so nothing else produces a 401.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorResponder)   // anonymous  -> 401
                        .accessDeniedHandler(errorResponder)        // wrong role -> 403
                )
                // Evaluated top to bottom, first match wins. The GET rules must precede the broader
                // path rules, or the admin rules would swallow public reads of /tags.
                .authorizeHttpRequests(auth -> auth
                        // Bean-validation failures can be reported via the servlet container's
                        // sendError mechanism, which internally forwards to GET /error to render the
                        // body. That forward re-enters this filter chain as its own request; without
                        // this rule it falls through to anyRequest().authenticated() and is
                        // rejected, swallowing the real 400. Still needed as the safety net for
                        // anything thrown outside the advice chain - a filter throwing before the
                        // dispatcher servlet, for one. See docs/security-error-handling.md.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/public/**", "/health").permitAll()

                        // Public reads: recipes are readable without an account.
                        .requestMatchers(HttpMethod.GET, "/recipes/**", "/ingredients/**", "/categories/**",
                                "/tags/**", "/units/**", "/media/**").permitAll()

                        // Reference data is curated by admins. Note the asymmetry on ingredients:
                        // creating one is open to any authenticated user below, but renaming a
                        // shared row silently rewrites every recipe referencing it, and deleting one
                        // would orphan them - a different kind of power from adding a missing row.
                        .requestMatchers(HttpMethod.POST, "/categories", "/tags", "/units").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/categories/**", "/tags/**", "/units/**",
                                "/ingredients/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/categories/**", "/tags/**", "/units/**",
                                "/ingredients/**").hasRole("ADMIN")

                        // Any authenticated user. PUT/DELETE /recipes/{id} are deliberately absent:
                        // "you may edit this recipe because you wrote it" depends on a row in the
                        // database, which a URL rule cannot express, so RecipeService performs the
                        // ownership check after loading the recipe. They fall through to
                        // anyRequest().authenticated() below.
                        .requestMatchers(HttpMethod.POST, "/ingredients", "/media", "/recipes").hasRole("USER")

                        // Routes that hand out credentials.
                        .requestMatchers(HttpMethod.POST, "/users/create").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/users/reinit-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/**").permitAll()

                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/users/**").hasRole("USER")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtDecoder),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

}
