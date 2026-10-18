package ilenreste.unpeu.recettesback.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger UI and {@code /v3/api-docs} are a development aid, switched off by
 * {@code application-prod.properties}. The routes are {@code permitAll()} in
 * {@code SecurityFilterConfig} whatever the profile, so what this proves is that under {@code prod}
 * they answer 404 because springdoc is not serving them - not 200, and not 401 from a locked door.
 * <p>
 * The committed {@code openapi.yaml} is the artifact either way; see {@code docs/openapi.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("prod")
class OpenApiProdProfileTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void underTheProdProfileNeitherTheSpecNorSwaggerUiIsServed() {
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            assertThat(restTemplate.getForEntity(path, String.class).getStatusCode())
                    .as("GET %s under the prod profile", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
