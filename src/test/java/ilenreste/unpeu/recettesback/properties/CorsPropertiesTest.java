package ilenreste.unpeu.recettesback.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A bad origin list must stop the application at startup, not surface as a front end that cannot reach
 * the API. The constructor is what binds the value, so it is exercised directly.
 */
class CorsPropertiesTest {

    private static final Duration MAX_AGE = Duration.ofHours(1);

    @Test
    void acceptsExplicitOrigins_withAndWithoutAPort() {
        CorsProperties properties = new CorsProperties(
                List.of("http://localhost:5173", "https://recettes.example.com"), MAX_AGE);

        assertThat(properties.allowedOrigins())
                .containsExactly("http://localhost:5173", "https://recettes.example.com");
        assertThat(properties.maxAge()).isEqualTo(MAX_AGE);
    }

    @Test
    void refusesAnEmptyList() {
        assertThatThrownBy(() -> new CorsProperties(List.of(), MAX_AGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one origin");
    }

    @Test
    void refusesAMissingList() {
        assertThatThrownBy(() -> new CorsProperties(null, MAX_AGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "http://localhost:*"})
    void refusesAnyWildcard(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of(origin), MAX_AGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no wildcards");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://recettes.example.com/", "https://recettes.example.com/app",
            "https://recettes.example.com?x=1", "recettes.example.com", "localhost:5173", "", "  "})
    void refusesAnythingThatIsNotABareOrigin(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of(origin), MAX_AGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.cors.allowed-origins");
    }

    @Test
    void refusesOneBadOriginAmongGoodOnes() {
        assertThatThrownBy(() -> new CorsProperties(List.of("http://localhost:5173", "*"), MAX_AGE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
