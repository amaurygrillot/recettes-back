package ilenreste.unpeu.recettesback.configuration;

import ilenreste.unpeu.recettesback.properties.CorsProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The CORS policy, handed to the security filter chain by {@link SecurityFilterConfig}.
 * <p>
 * The chain runs Spring's {@code CorsFilter} ahead of authentication, which is what makes a
 * preflight ({@code OPTIONS}) work on a protected route: the browser sends it without a token, and
 * it is answered before anything asks who is calling. It also never reaches
 * {@code JwtAuthenticationFilter}, so a stale token cannot turn it into a 401. Rationale and how to
 * add an origin: docs/cors.md.
 */
@Log4j2
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOrigins(properties.allowedOrigins());
        policy.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        policy.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        // Not readable from a cross-origin script unless exposed: Location is how a create is
        // followed, Retry-After is how a 429 or a 503 tells the client when to try again.
        policy.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER));
        // The token travels in the Authorization header, not in a cookie, so the browser has no
        // credentials to attach and none are to be allowed.
        policy.setAllowCredentials(false);
        policy.setMaxAge(properties.maxAge());

        log.info("CORS allowed origins: {}", properties.allowedOrigins());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", policy);
        return source;
    }
}
