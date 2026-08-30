package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.filters.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityFilterConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(
                                org.springframework.security.config.http.SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        // Bean-validation failures (any @Valid @RequestBody) are reported via the servlet
                        // container's sendError mechanism, which internally forwards to GET /error to render
                        // the body. That forward re-enters this filter chain as its own request; without this
                        // rule it falls through to anyRequest().authenticated() and gets rejected (403,
                        // swallowing the real 400) since it's unauthenticated and no AuthenticationEntryPoint
                        // is configured. See docs/security-error-handling.md.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/public/**", "/health").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/users/create").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/users/reinit-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/**").permitAll()
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

