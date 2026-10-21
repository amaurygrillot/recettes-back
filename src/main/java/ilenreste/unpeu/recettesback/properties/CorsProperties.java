package ilenreste.unpeu.recettesback.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Which browser origins may call this API. See docs/cors.md for why the list is explicit.
 * <p>
 * Lives in {@code properties} because it is bound data; the {@code CorsConfigurationSource} built
 * from it is wiring and lives in {@code configuration.CorsConfig}.
 * <p>
 * Validation is in the constructor, so a bad value stops the application at startup instead of
 * surfacing as a front end that "mysteriously" cannot reach the API. A wildcard is refused outright:
 * the point of this class is that the list is written down, and {@code *} is the absence of one.
 *
 * @param allowedOrigins origins, each exactly as a browser sends it in the {@code Origin} header:
 *                       scheme, host and optional port, with no path and no trailing slash
 *                       ({@code https://recettes.example.com}). Set through
 *                       {@code CORS_ALLOWED_ORIGINS} as a comma-separated list
 * @param maxAge         how long a browser may cache a preflight answer. Browsers cap this on their
 *                       own (Chromium at 2 hours), so a larger value is silently shortened
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        @DefaultValue("1h") Duration maxAge
) {

    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException(
                    "app.cors.allowed-origins must list at least one origin (set CORS_ALLOWED_ORIGINS)");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
        allowedOrigins.forEach(CorsProperties::requireExplicitOrigin);
    }

    private static void requireExplicitOrigin(String origin) {
        if (origin.contains("*")) {
            throw new IllegalArgumentException(
                    "app.cors.allowed-origins must name each origin explicitly, no wildcards: '" + origin + "'");
        }
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("app.cors.allowed-origins has an unparseable origin: '" + origin + "'",
                    invalid);
        }
        boolean isBareOrigin = uri.getScheme() != null && uri.getHost() != null
                && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null
                && uri.getRawUserInfo() == null;
        if (!isBareOrigin) {
            throw new IllegalArgumentException("app.cors.allowed-origins must be scheme://host[:port] with no path or "
                    + "trailing slash, as a browser sends it in the Origin header: '" + origin + "'");
        }
    }
}
